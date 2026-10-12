"""Exercise the exact dependency analyzer embedded in the reusable workflow."""
import collections
from pathlib import Path
import textwrap
import re
import unittest
from unittest.mock import patch
import contextlib
import io
import json
import os
import tempfile
import subprocess

workflow = Path(__file__).resolve().parents[2] / ".github/workflows/connector-it-reusable.yml"
block = workflow.read_text().split("# BEGIN dependency analyzer", 1)[1].split("# END dependency analyzer", 1)[0]
source = re.search(r"python3 - <<'PY'\n(.*?)\n[ \t]*PY\s*$", block, re.S).group(1)
namespace = {"__name__": "it_selection_test"}
exec(compile(textwrap.dedent(source), str(workflow), "exec"), namespace)
graph = namespace["dependency_graph"]
select = namespace["affected_modules"]


def pom(artifact, dependencies=(), modules=(), parent=None):
    parent_xml = "" if parent is None else (
        "<parent><groupId>test</groupId><artifactId>" + parent +
        "</artifactId><version>1</version></parent>")
    return ("<project>" + parent_xml + "<groupId>test</groupId><artifactId>" +
            artifact + "</artifactId><dependencies>" +
            "".join("<dependency><groupId>test</groupId><artifactId>" + d +
                    "</artifactId></dependency>" for d in dependencies) +
            "</dependencies><modules>" +
            "".join("<module>" + m + "</module>" for m in modules) +
            "</modules></project>")


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.poms = {
            "pom.xml": pom("root"),
            "file-storages/pom.xml": pom("storage", modules=("ftp",)),
            "file-storages/ftp/pom.xml": pom("ftp", parent="storage"),
            "connectors-common/file-core/pom.xml": pom("file-core", ("ftp",)),
            "connectors/csv/pom.xml": pom("csv", ("file-core",)),
            "connectors/mysql/pom.xml": pom("mysql"),
        }
        self.candidates = {"connectors/csv", "connectors/mysql"}

    def selected(self, changed, old=None, strategy="affected"):
        versions = [self.poms] if old is None else [old, self.poms]
        return select(changed, [graph(p) for p in versions],
                      set().union(*(set(p) for p in versions)), self.candidates, strategy)

    def test_transitive_storage_change_excludes_database(self):
        self.assertEqual(["connectors/csv"], self.selected(["file-storages/ftp/src/main/Ftp.java"]))

    def test_aggregate_pom_only_affects_descendants_and_consumers(self):
        self.assertEqual(["connectors/csv"], self.selected(["file-storages/pom.xml"]))

    def test_common_change_and_explicit_all(self):
        changed = ["connectors-common/file-core/pom.xml"]
        self.assertEqual(["connectors/csv"], self.selected(changed))
        self.assertEqual(sorted(self.candidates), self.selected(changed, strategy="all"))

    def test_removed_module_uses_previous_graph(self):
        old = dict(self.poms)
        del self.poms["file-storages/ftp/pom.xml"]
        self.assertEqual(["connectors/csv"], self.selected(["file-storages/ftp/pom.xml"], old))

    def test_root_and_unrelated_changes(self):
        self.assertEqual(sorted(self.candidates), self.selected(["pom.xml"]))
        self.assertEqual([], self.selected(["README.md"]))

    def test_rejects_shell_characters_and_traversal(self):
        validate = namespace["validate_modules"]
        validate(["connectors/mysql-connector"])
        for module in ("connectors/a'$(id)", "connectors/../outside", "connectors/a\nb"):
            with self.assertRaises(ValueError):
                validate([module])

    def test_unresolved_coordinate_reports_pom(self):
        self.poms["connectors/mysql/pom.xml"] = pom("mysql", ("${unknown}",))
        with self.assertRaisesRegex(ValueError, "connectors/mysql/pom.xml"):
            graph(self.poms)

    def test_profile_dependency_is_conservative(self):
        self.poms["connectors/mysql/pom.xml"] = pom("mysql").replace(
            "</project>", "<profiles><profile><dependencies><dependency>"
            "<groupId>test</groupId><artifactId>ftp</artifactId>"
            "</dependency></dependencies></profile></profiles></project>")
        self.assertEqual(sorted(self.candidates), self.selected(["file-storages/ftp/pom.xml"]))


class MainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for module in ("connectors/csv", "connectors/mysql", "other/custom", "connectors/unsafe'path"):
            (self.root / module / "src/it/java").mkdir(parents=True)
            (self.root / module / "pom.xml").write_text(pom(module.rsplit("/", 1)[-1]))
        self.poms = {str(path.relative_to(self.root)): path.read_text()
                     for path in self.root.glob("**/pom.xml")}
        self.calls = []
        self.missing_base = False
        self.fail_fetch = False
        self.fail_diff = False

    def fake_git(self, *args):
        self.calls.append(args)
        if args[0] == "fetch":
            if self.fail_fetch:
                raise subprocess.CalledProcessError(128, ["git", *args])
            self.missing_base = False
            return ""
        if args[0] == "cat-file":
            if self.missing_base:
                raise subprocess.CalledProcessError(128, ["git", *args])
            return ""
        if args[0] in ("merge-base", "rev-parse"):
            return "baseline"
        if args[0] == "diff":
            if self.fail_diff:
                raise subprocess.CalledProcessError(128, ["git", *args])
            return "connectors/csv/src/main/Csv.java"
        if args[0] == "ls-tree":
            return "\n".join(self.poms)
        if args[0] == "show":
            return self.poms[args[1].split(":", 1)[1]]
        raise AssertionError(args)

    def run_main(self, **env):
        previous = os.getcwd()
        output = self.root / "output"
        output.unlink(missing_ok=True)
        defaults = {"GITHUB_OUTPUT": str(output), "CONNECTORS_OVERRIDE": "",
                    "COMMON_STRATEGY": "affected", "EVENT_NAME": "push",
                    "BEFORE_SHA": "baseline"}
        defaults.update(env)
        try:
            os.chdir(self.root)
            with patch.dict(os.environ, defaults), patch.dict(namespace, {"git": self.fake_git}), contextlib.redirect_stdout(io.StringIO()):
                namespace["main"]()
        finally:
            os.chdir(previous)
        return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def test_main_ignores_unsupported_suites_in_push_and_pr(self):
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                result = self.run_main(EVENT_NAME=event, BASE_REF="develop")
                self.assertEqual(["connectors/csv"], json.loads(result["connectors"]))
                self.assertEqual("true", result["has_changes"])
        self.assertIn(("fetch", "origin", "+refs/heads/develop:refs/remotes/origin/develop"), self.calls)

    def test_main_fallback_only_selects_safe_supported_candidates(self):
        self.poms["connectors/csv/pom.xml"] = "<broken>"
        result = self.run_main()
        self.assertEqual(["connectors/csv", "connectors/mysql"], json.loads(result["connectors"]))

    def test_main_override_remains_fail_fast(self):
        for module in ("other/custom", "connectors/unsafe'path", "connectors/missing"):
            with self.subTest(module=module), self.assertRaises(ValueError):
                self.run_main(CONNECTORS_OVERRIDE=module)

    def test_main_valid_override(self):
        result = self.run_main(CONNECTORS_OVERRIDE="connectors/mysql")
        self.assertEqual(["connectors/mysql"], json.loads(result["connectors"]))
        self.assertEqual([], self.calls)

    def test_missing_push_baseline_is_fetched_before_diff(self):
        self.missing_base = True
        result = self.run_main()
        self.assertEqual(["connectors/csv"], json.loads(result["connectors"]))
        fetch = ("fetch", "--no-tags", "--depth=1", "origin", "baseline")
        self.assertLess(self.calls.index(fetch), self.calls.index(("diff", "--name-only", "baseline", "HEAD")))

    def test_unavailable_push_baseline_falls_back_instead_of_crashing(self):
        self.missing_base = True
        self.fail_fetch = True
        result = self.run_main()
        self.assertEqual(sorted(self.candidates()), json.loads(result["connectors"]))
        self.assertEqual("true", result["has_changes"])
        self.assertFalse(any(call[0] == "diff" for call in self.calls))

    def candidates(self):
        return {"connectors/csv", "connectors/mysql"}

    def test_pr_fetch_failure_and_diff_failure_write_fallback_outputs(self):
        for mode in ("pr_fetch", "diff"):
            with self.subTest(mode=mode):
                self.fail_fetch = mode == "pr_fetch"
                self.fail_diff = mode == "diff"
                result = self.run_main(EVENT_NAME="pull_request", BASE_REF="develop")
                self.assertEqual(sorted(self.candidates()), json.loads(result["connectors"]))
                self.assertEqual("true", result["has_changes"])


if __name__ == "__main__":
    unittest.main()
