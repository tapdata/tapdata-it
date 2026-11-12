"""Execute the workflow's connection setup before Maven can fall back to JSON."""
import contextlib
import io
import os
from pathlib import Path
import re
import tempfile
import textwrap
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/connector-it-reusable.yml"
BLOCK = WORKFLOW.read_text().split("# BEGIN IT connection configuration", 1)[1].split(
    "# END IT connection configuration", 1)[0]
SOURCE = textwrap.dedent(re.search(r"python3 - <<'PY'\n(.*?)\n[ \t]*PY\s*$", BLOCK, re.S)[1])


class EnvironmentTest(unittest.TestCase):
    def configure(self, **settings):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "env"
            summary = Path(directory) / "summary"
            environment = dict(CONNECTOR="connectors/aliyun-rds-mysql-connector",
                               DBFORGE_CONNECTORS="connectors/mysql-connector, connectors/aliyun-rds-mysql-connector",
                               CONNECTOR_IT_DBFORGE_URL="http://dbforge.example:8080",
                               CONNECTOR_IT_DBFORGE_TOKEN="test-secret-never-logged",
                               GITHUB_ENV=str(output), GITHUB_STEP_SUMMARY=str(summary))
            environment.update(settings)
            console = io.StringIO()
            with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stdout(console):
                exec(compile(SOURCE, str(WORKFLOW), "exec"), {"__name__": "__main__"})
            result = output.read_text(), summary.read_text(), console.getvalue()
            self.assertNotIn("test-secret-never-logged", "".join(result))
            return result

    def test_selected_module_uses_dbforge(self):
        environment, summary, _ = self.configure()
        self.assertEqual("CONNECTOR_IT_CONFIG_SOURCE=dbforge\n", environment)
        self.assertIn("compatible base engines", summary)

    def test_unselected_module_keeps_json_without_credentials(self):
        environment, _, _ = self.configure(CONNECTOR="connectors/tidb-connector",
                                           CONNECTOR_IT_DBFORGE_URL="", CONNECTOR_IT_DBFORGE_TOKEN="")
        self.assertEqual("CONNECTOR_IT_CONFIG_SOURCE=json\n", environment)

    def test_missing_credentials_fail_before_maven(self):
        for name in ("CONNECTOR_IT_DBFORGE_URL", "CONNECTOR_IT_DBFORGE_TOKEN"):
            with self.subTest(name=name), self.assertRaisesRegex(SystemExit, name):
                self.configure(**{name: ""})

    def test_empty_list_preserves_existing_callers(self):
        environment, _, _ = self.configure(DBFORGE_CONNECTORS="")
        self.assertEqual("CONNECTOR_IT_CONFIG_SOURCE=json\n", environment)


if __name__ == "__main__":
    unittest.main()
