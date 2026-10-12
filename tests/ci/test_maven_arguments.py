"""Execute the composite action shell with a fake Maven command, without network."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github/actions/maven-retry"
SCRIPT = textwrap.dedent((ACTION / "action.yml").read_text().split("      run: |\n", 1)[1])


class MavenArgumentsTest(unittest.TestCase):
    def execute(self, arguments, settings=False):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            output = temp / "argv.json"
            fake = temp / "mvn"
            fake.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                            'with open(os.environ["ARGV_OUTPUT"], "w") as out:\n'
                            '    json.dump(sys.argv[1:], out)\n')
            fake.chmod(0o755)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"],
                       MAVEN_ARGUMENTS=arguments, GITHUB_ACTION_PATH=str(ACTION),
                       ARGV_OUTPUT=str(output), MAVEN_RETRY_MAX_ATTEMPTS="1",
                       MAVEN_JOB_SETTINGS="")
            if settings:
                settings_path = temp / "settings with spaces.xml"
                settings_path.write_text("<settings/>")
                env["MAVEN_JOB_SETTINGS"] = str(settings_path)
            result = subprocess.run(["bash", "-eo", "pipefail"], input=SCRIPT,
                                    text=True, capture_output=True, env=env)
            argv = json.loads(output.read_text()) if output.exists() else None
            return result, argv, env["MAVEN_JOB_SETTINGS"]

    def test_yaml_trailing_newline_and_blank_lines(self):
        result, argv, _ = self.execute("\n-U\n\ninstall\n  \n-B\n\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["-U", "install", "-B"], argv)

    def test_spaces_backslashes_and_shell_text_are_literal(self):
        arguments = ["install", "-Dlabel=two words", r"-Dpath=C:\cache", "-Dvalue=$(false)"]
        result, argv, _ = self.execute("\n".join(arguments))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(arguments, argv)

    def test_job_settings_remain_a_single_argument(self):
        result, argv, settings = self.execute("install\n", settings=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--settings", settings, "install"], argv)

    def test_empty_input_fails_before_maven(self):
        for arguments in ("", "\n \t\n"):
            with self.subTest(arguments=arguments):
                result, argv, _ = self.execute(arguments)
                self.assertEqual(2, result.returncode)
                self.assertIsNone(argv)
                self.assertIn("Maven arguments must not be empty", result.stdout)


if __name__ == "__main__":
    unittest.main()
