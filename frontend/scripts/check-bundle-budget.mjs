import { readdir, stat } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const assetsDir = join(scriptDir, "..", "..", "target", "frontend-static", "assets");
const limits = {
  maxJavaScriptBytes: 500 * 1024,
  totalApplicationJavaScriptBytes: 800 * 1024,
  lazyFormulaJavaScriptBytes: 300 * 1024,
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
const lazyFormulaScripts = scripts.filter((asset) => asset.name.startsWith("katex-"));
const applicationScripts = scripts.filter((asset) => !lazyFormulaScripts.includes(asset));
const largestScript = scripts.reduce((largest, asset) => asset.bytes > largest.bytes ? asset : largest, { name: "", bytes: 0 });
const totalApplicationJavaScriptBytes = applicationScripts.reduce((total, asset) => total + asset.bytes, 0);
const lazyFormulaJavaScriptBytes = lazyFormulaScripts.reduce((total, asset) => total + asset.bytes, 0);
const totalCssBytes = styles.reduce((total, asset) => total + asset.bytes, 0);
const failures = [];
if (largestScript.bytes > limits.maxJavaScriptBytes) failures.push(`largest JS ${largestScript.name} is ${largestScript.bytes} bytes`);
if (totalApplicationJavaScriptBytes > limits.totalApplicationJavaScriptBytes) {
  failures.push(`application JS is ${totalApplicationJavaScriptBytes} bytes`);
}
if (lazyFormulaJavaScriptBytes > limits.lazyFormulaJavaScriptBytes) {
  failures.push(`lazy formula JS is ${lazyFormulaJavaScriptBytes} bytes`);
}
if (totalCssBytes > limits.totalCssBytes) failures.push(`total CSS is ${totalCssBytes} bytes`);
if (failures.length) {
  throw new Error(`Bundle budget exceeded: ${failures.join("; ")}`);
}
console.log(`Bundle budget passed: largest JS ${largestScript.bytes} B, application JS ${totalApplicationJavaScriptBytes} B, lazy formula JS ${lazyFormulaJavaScriptBytes} B, total CSS ${totalCssBytes} B.`);
