import { readdir, stat } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const assetsDir = join(scriptDir, "..", "..", "target", "frontend-static", "assets");
const limits = {
  maxJavaScriptBytes: 500 * 1024,
  totalJavaScriptBytes: 750 * 1024,
  totalCssBytes: 400 * 1024
};
const files = await readdir(assetsDir);
const assets = await Promise.all(files.map(async (name) => ({
  name,
  extension: extname(name),
  bytes: (await stat(join(assetsDir, name))).size
})));
const scripts = assets.filter((asset) => asset.extension === ".js");
const styles = assets.filter((asset) => asset.extension === ".css");
const largestScript = scripts.reduce((largest, asset) => asset.bytes > largest.bytes ? asset : largest, { name: "", bytes: 0 });
const totalJavaScriptBytes = scripts.reduce((total, asset) => total + asset.bytes, 0);
const totalCssBytes = styles.reduce((total, asset) => total + asset.bytes, 0);
const failures = [];
if (largestScript.bytes > limits.maxJavaScriptBytes) failures.push(`largest JS ${largestScript.name} is ${largestScript.bytes} bytes`);
if (totalJavaScriptBytes > limits.totalJavaScriptBytes) failures.push(`total JS is ${totalJavaScriptBytes} bytes`);
if (totalCssBytes > limits.totalCssBytes) failures.push(`total CSS is ${totalCssBytes} bytes`);
if (failures.length) {
  throw new Error(`Bundle budget exceeded: ${failures.join("; ")}`);
}
console.log(`Bundle budget passed: largest JS ${largestScript.bytes} B, total JS ${totalJavaScriptBytes} B, total CSS ${totalCssBytes} B.`);
