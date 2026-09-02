/**
 * Refreshes the installed copy of the `uma-scoring` package after the Kotlin Multiplatform module has been rebuilt.
 *
 * `uma-scoring` is declared in package.json with the `file:` protocol, which Yarn resolves by *copying* the directory into `node_modules` at install
 * time. Rebuilding the KMP module updates the dist directory but leaves that copy untouched, so Jest and Metro keep reading stale scoring math until
 * the package is reinstalled. Copying directly is far quicker than a reinstall and keeps `yarn build:scoring` a single step.
 *
 * Run with: `yarn build:scoring` to rebuild and copy, or `yarn sync:scoring` to copy an already-built dist without rebuilding.
 */
import * as fs from "node:fs"
import * as path from "node:path"

const REPO_ROOT = path.resolve(__dirname, "..")
const PACKAGE_NAME = "uma-scoring"

/**
 * Reports why the sync cannot proceed and exits with a failure code.
 *
 * @param message The reason, printed to stderr.
 * @returns Never - the process exits.
 */
const fail = (message: string): never => {
    console.error(`[sync-scoring] ${message}`)
    process.exit(1)
}

// Take the source directory from the dependency declaration itself, so Yarn and this script can never disagree about which build output is canonical.
const pkg = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, "package.json"), "utf8")) as { dependencies?: Record<string, string> }
const spec = pkg.dependencies?.[PACKAGE_NAME] ?? ""
if (!spec.startsWith("file:")) fail(`Expected package.json to declare "${PACKAGE_NAME}" with the file: protocol, got "${spec || "nothing"}".`)

const distDir = path.resolve(REPO_ROOT, spec.slice("file:".length))
const installedDir = path.join(REPO_ROOT, "node_modules", PACKAGE_NAME)

if (!fs.existsSync(distDir)) fail(`Distribution directory not found: ${distDir}. Build it first with \`yarn build:scoring\`.`)

// Replace rather than merge so files deleted from the KMP output do not linger in the installed copy.
fs.rmSync(installedDir, { recursive: true, force: true })
fs.cpSync(distDir, installedDir, { recursive: true })
console.log(`[sync-scoring] Refreshed node_modules/${PACKAGE_NAME} from ${path.relative(REPO_ROOT, distDir)}.`)
