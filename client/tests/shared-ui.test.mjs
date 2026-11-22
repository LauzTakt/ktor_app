import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const client = new URL("../", import.meta.url);

test("Ktor ships the stylesheet built from the current daisyUI controls", async () => {
  const manifest = import.meta.resolve("@tailwindcss/cli/package.json");
  const { bin } = JSON.parse(await readFile(new URL(manifest), "utf8"));
  const cli = fileURLToPath(new URL(bin.tailwindcss, manifest));
  const built = execFileSync(process.execPath, [cli, "-i", "rail-ui.css", "--minify"], {
    cwd: client, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
  });
  const shipped = await readFile(new URL("../src/main/resources/static/vendor/daisyui.css", client), "utf8");
  assert.ok(built.trimEnd() === shipped.trimEnd(), "Run npm run build:ui and include the generated stylesheet.");
});

test("the Vite preview uses the same root stylesheets as the packaged assistant", async (t) => {
  const cacheDir = await mkdtemp(join(tmpdir(), "train-twin-vite-"));
  t.after(() => rm(cacheDir, { recursive: true, force: true }));
  const server = await createServer({
    root: fileURLToPath(client),
    cacheDir,
    logLevel: "error",
    server: { middlewareMode: true, hmr: false, watch: null },
    optimizeDeps: { noDiscovery: true },
  });
  try {
    const entry = await readFile(new URL("index.html", client), "utf8");
    const html = await server.transformIndexHtml("/assistant/", entry);
    for (const href of ["/theme.css", "/loki.css"]) {
      assert.ok(html.includes(`href="${href}"`), `Expected Ktor stylesheet ${href}`);
    }
    assert.ok(server.config.server.proxy["/vendor/daisyui.css"]);
  } finally {
    await server.close();
  }
});
