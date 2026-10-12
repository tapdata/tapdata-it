"""Guard against building every common module for every IT matrix entry."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DependencyInstallTest(unittest.TestCase):
    def test_only_target_dependency_chain_is_installed(self):
        workflow = (ROOT / ".github/workflows/connector-it-reusable.yml").read_text()
        self.assertNotIn("common-modules", workflow)
        self.assertNotIn("Install connectors-common modules", workflow)
        installs = [step for step in workflow.split("      - name: ")[1:]
                    if "            install\n" in step]
        self.assertEqual(1, len(installs))
        self.assertTrue(installs[0].startswith("Install dependency chain (skip all tests)\n"))
        self.assertIn("            -pl\n            ${{ matrix.connector }}\n            -am\n", installs[0])
        self.assertIn("            -DskipTests\n", installs[0])
        self.assertIn("            -DskipITs=true\n", installs[0])
        integration = workflow.split("      - name: Run integration tests\n", 1)[1]
        self.assertIn("working-directory: ${{ matrix.connector }}", integration)
        self.assertIn("failsafe:integration-test", integration)
        self.assertIn("failsafe:verify", integration)
        self.assertNotIn("            -am\n", integration)


if __name__ == "__main__":
    unittest.main()
