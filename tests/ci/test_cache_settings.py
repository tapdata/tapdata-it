"""Run the exact merge/prune code embedded in deployment and workflow assets."""
import contextlib
import io
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def embedded(path, begin, end, heredoc=False):
    text = (ROOT / path).read_text().split(begin, 1)[1].split(end, 1)[0]
    if heredoc:
        text = text.split("<<'PY'\n", 1)[1].rsplit("\n", 2)[0]
    scope = {"__name__": "embedded_test"}
    exec(compile(textwrap.dedent(text), str(path), "exec"), scope)
    return scope


MERGE = embedded(".github/workflows/connector-it-reusable.yml",
                 "# BEGIN Maven settings merge", "# END Maven settings merge", True)
PRUNE = embedded("deploy/k3s/runner-it/runner-cache-prune.yaml",
                 "# BEGIN cache prune", "# END cache prune")
BASE = """<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
<mirrors><mirror><id>tapdata-nexus</id><mirrorOf>*</mirrorOf>
<url>https://nexus.tapdata.net/repository/maven-public/</url></mirror></mirrors>
<servers><server><id>existing</id><username>base</username></server></servers>
<pluginGroups><pluginGroup>base.plugin</pluginGroup></pluginGroups></settings>"""


class MergeTest(unittest.TestCase):
    def test_workflow_shell_and_heredoc_execute_together(self):
        workflow = (ROOT / ".github/workflows/connector-it-reusable.yml").read_text()
        step = workflow.split("- name: Configure Maven settings and SSL", 1)[1]
        script = re.split(r"\n      (?=#|-)", step.split("        run: |\n", 1)[1], maxsplit=1)[0]
        script = textwrap.dedent(script)
        syntax = subprocess.run(["bash", "-n"], input=script, text=True, capture_output=True)
        self.assertEqual(0, syntax.returncode, syntax.stderr)
        self.assertNotIn("here-document", syntax.stderr)
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / ".m2").mkdir()
            base = home / ".m2/settings.xml"
            base.write_text(BASE)
            env_file = home / "github-env"
            for secret in ("", "<settings><servers><server><id>test</id></server></servers></settings>"):
                env_file.write_text("")
                env = dict(os.environ, HOME=directory, RUNNER_TEMP=directory,
                           GITHUB_ENV=str(env_file), MAVEN_SETTINGS_CONTENT=secret)
                result = subprocess.run(["bash", "-e"], input=script, text=True,
                                        capture_output=True, env=env)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(BASE, base.read_text())
                lines = env_file.read_text().splitlines()
                settings = [line.split("=", 1)[1] for line in lines
                            if line.startswith("MAVEN_JOB_SETTINGS=")]
                self.assertEqual(bool(secret), bool(settings))
                if settings:
                    merged = Path(settings[0])
                    self.assertEqual(0o600, merged.stat().st_mode & 0o777)
                    self.assertEqual("tapdata-nexus", ET.parse(merged).findtext("./mirrors/mirror/id"))

    def test_auth_profiles_merge_preserves_runner_routing(self):
        secret = """<settings><servers><server><id>existing</id><username>new</username></server>
        <server><id>extra</id><password>TEST_ONLY</password></server></servers>
        <profiles><profile><id>custom</id></profile></profiles>
        <activeProfiles><activeProfile>custom</activeProfile></activeProfiles>
        <mirrors><mirror><id>other</id><mirrorOf>*</mirrorOf><url>https://other.invalid</url></mirror></mirrors>
        <localRepository>/shared/unwanted</localRepository>
        <pluginGroups><pluginGroup>secret.plugin</pluginGroup></pluginGroups></settings>"""
        root = ET.fromstring(MERGE["merge_settings"](BASE, secret))
        self.assertEqual("tapdata-nexus", root.findtext("./mirrors/mirror/id"))
        self.assertEqual("new", root.findtext("./servers/server/username"))
        self.assertEqual(2, len(root.findall("./servers/server")))
        self.assertIsNone(root.find("localRepository"))
        self.assertEqual("custom", root.findtext("./profiles/profile/id"))
        self.assertEqual("custom", root.findtext("./activeProfiles/activeProfile"))
        self.assertEqual({"base.plugin", "secret.plugin", "org.sonarsource.scanner.maven"},
                         {n.text for n in root.findall("./pluginGroups/pluginGroup")})

    def test_missing_mirror_fails_without_mutating_base(self):
        with self.assertRaisesRegex(ValueError, "tapdata-nexus"):
            MERGE["merge_settings"]("<settings/>", "<settings/>")
        self.assertIn("tapdata-nexus", BASE)


class PruneTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "_runner-cache"
        self.root.mkdir()
        self.live = self.cache("runner-scan-live")
        self.old = self.cache("runner-scan-old")
        self.unknown = self.cache("manual-data")
        self.now = 1000000
        for directory in (self.live, self.old, self.unknown):
            marker = directory / ".orphaned"
            marker.touch()
            os.utime(marker, (0, 0))

    def cache(self, name):
        directory = self.root / name
        directory.mkdir()
        (directory / "keep.jar").write_text("fixture")
        return directory

    def run_prune(self, dry_run=False, live=None):
        live = {self.live.name} if live is None else live
        with patch.dict(PRUNE, {"live_pods": lambda: live}), contextlib.redirect_stdout(io.StringIO()):
            return PRUNE["prune"](self.root, live, self.now, 7 * 86400, dry_run)

    def test_only_expired_orphans_removed(self):
        self.assertEqual([self.old.name], self.run_prune())
        self.assertTrue(self.live.exists())
        self.assertTrue(self.unknown.exists())
        self.assertFalse(self.old.exists())

    def test_dry_run_does_not_delete_or_modify_markers(self):
        self.assertEqual([self.old.name], self.run_prune(dry_run=True))
        self.assertTrue(self.old.exists())
        self.assertTrue((self.live / ".orphaned").exists())

    def test_new_orphan_gets_grace_period(self):
        (self.old / ".orphaned").unlink()
        self.assertEqual([], self.run_prune())
        self.assertTrue((self.old / ".orphaned").is_file())

    def test_symlink_target_never_deleted(self):
        (self.root / "runner-linked").symlink_to(self.unknown, target_is_directory=True)
        self.run_prune()
        self.assertTrue(self.unknown.exists())

    def test_second_api_check_protects_newly_live_pod(self):
        with patch.dict(PRUNE, {"live_pods": lambda: {self.old.name}}), contextlib.redirect_stdout(io.StringIO()):
            result = PRUNE["prune"](self.root, {self.live.name}, self.now, 7 * 86400, False)
        self.assertEqual([], result)
        self.assertTrue(self.old.exists())


if __name__ == "__main__":
    unittest.main()
