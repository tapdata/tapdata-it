"""Guard against building every common module for every IT matrix entry."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DependencyInstallTest(unittest.TestCase):
    def test_only_target_dependency_chain_is_installed(self):
        workflow = (ROOT / ".github/workflows/connector-it-reusable.yml").read_text()
        self.assertNotIn("common-modules", workflow)
        self.assertNotIn("Install connectors-common modules", workflow)
        steps = workflow.split("      - name: ")[1:]
        installs = [step for step in steps if "            install\n" in step]
        self.assertEqual(
            ["Install pinned SQL core", "Install dependency chain (skip all tests)"],
            [step.splitlines()[0] for step in installs],
        )
        bootstrap, target = installs
        checkout = next(step for step in steps if step.startswith("Check out pinned SQL core\n"))
        for step in (checkout, bootstrap):
            self.assertIn("if: inputs.install_enterprise_sql_core && github.repository == 'tapdata/tapdata-connectors-enterprise'", step)
        self.assertIn("repository: tapdata/tapdata-connectors\n", checkout)
        self.assertIn("ref: 5ec89e425667ee8993c306391e7f92264f9f1748\n", checkout)
        self.assertIn("persist-credentials: false", checkout)
        self.assertIn("path: .ci-deps/tapdata-connectors\n", checkout)
        self.assertIn("working-directory: .ci-deps/tapdata-connectors\n", bootstrap)
        self.assertIn("            -pl\n            connectors-common/sql-core\n            -am\n", bootstrap)
        self.assertIn("            -pl\n            ${{ matrix.connector }}\n            -am\n", target)
        self.assertIn("            -DskipTests\n", target)
        self.assertIn("            -DskipITs=true\n", target)
        integration = workflow.split("      - name: Run integration tests\n", 1)[1]
        self.assertIn("working-directory: ${{ matrix.connector }}", integration)
        self.assertIn("failsafe:integration-test", integration)
        self.assertIn("failsafe:verify", integration)
        self.assertNotIn("            -am\n", integration)


if __name__ == "__main__":
    unittest.main()
