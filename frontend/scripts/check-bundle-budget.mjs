import { readFile, stat } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const outputDir = join(scriptDir, "..", "..", "target", "frontend-static");
const manifestPath = join(outputDir, ".vite", "manifest.json");
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
const entries = Object.entries(manifest);

const limits = {
  initialJavaScriptBytes: 320 * 1024,
  maxRouteJavaScriptBytes: 320 * 1024,
  maxRegularChunkBytes: 500 * 1024,
  formulaRendererJavaScriptBytes: 300 * 1024,
  mermaidCoreChunkBytes: 650 * 1024,
  flowchartRendererJavaScriptBytes: 800 * 1024,
  totalCssBytes: 400 * 1024,
  totalArtifactJavaScriptWarningBytes: 4 * 1024 * 1024
};

function findKey(predicate, description) {
  const match = entries.find(([key, value]) => predicate(key, value));
  if (!match) throw new Error(`Bundle manifest is missing ${description}.`);
  return match[0];
}

function collectStaticFiles(startKeys) {
  const pending = [...startKeys];
  const visitedKeys = new Set();
  const files = new Set();

  while (pending.length) {
    const key = pending.pop();
    if (!key || visitedKeys.has(key)) continue;
    const entry = manifest[key];
    if (!entry) throw new Error(`Bundle manifest references unknown entry: ${key}`);
    visitedKeys.add(key);
    if (entry.file) files.add(entry.file);
    for (const cssFile of entry.css ?? []) files.add(cssFile);
    pending.push(...(entry.imports ?? []));
  }
  return { files, keys: visitedKeys };
}

async function totalBytes(files, extension) {
  const matchingFiles = [...files].filter((file) => extname(file) === extension);
  const sizes = await Promise.all(matchingFiles.map(async (file) => (await stat(join(outputDir, file))).size));
  return sizes.reduce((total, bytes) => total + bytes, 0);
}

function without(files, excluded) {
  return new Set([...files].filter((file) => !excluded.has(file)));
}

function formatKiB(bytes) {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

const initialKeys = entries.filter(([, value]) => value.isEntry).map(([key]) => key);
const initial = collectStaticFiles(initialKeys);
const initialJavaScriptBytes = await totalBytes(initial.files, ".js");

const routeEntries = entries.filter(([key, value]) => value.isDynamicEntry && /^src\/(views|layouts)\/.+\.vue$/.test(key));
const routeBudgets = await Promise.all(routeEntries.map(async ([key]) => {
  const route = collectStaticFiles([key]);
  const incrementalFiles = without(route.files, initial.files);
  return { key, bytes: await totalBytes(incrementalFiles, ".js"), files: incrementalFiles, keys: route.keys };
}));
const largestRoute = routeBudgets.reduce((largest, route) => route.bytes > largest.bytes ? route : largest, { key: "", bytes: 0, files: new Set(), keys: new Set() });

const regularDeliveryFiles = new Set(initial.files);
const regularDeliveryKeys = new Set(initial.keys);
for (const route of routeBudgets) {
  for (const file of route.files) regularDeliveryFiles.add(file);
  for (const key of route.keys) regularDeliveryKeys.add(key);
}

const formulaKey = findKey((key) => key === "node_modules/katex/dist/katex.mjs", "the KaTeX renderer entry");
const formulaFiles = without(collectStaticFiles([formulaKey]).files, initial.files);
const formulaRendererJavaScriptBytes = await totalBytes(formulaFiles, ".js");

const mermaidComponentKey = findKey((key) => key === "src/components/MermaidDiagram.vue", "the Mermaid component entry");
const mermaidCoreKey = findKey((key, value) => value.file?.includes("/mermaid.core-"), "the Mermaid core entry");
const flowchartKey = findKey((key, value) => value.file?.includes("/flowDiagram-"), "the Mermaid flowchart entry");
const mermaidCoreEntry = manifest[mermaidCoreKey];
if (!(manifest[mermaidComponentKey].dynamicImports ?? []).includes(mermaidCoreKey)) {
  throw new Error("Mermaid core is no longer lazy-loaded by MermaidDiagram.");
}
if (!(mermaidCoreEntry.dynamicImports ?? []).includes(flowchartKey)) {
  throw new Error("Mermaid flowchart is no longer lazy-loaded by Mermaid core.");
}

const regularMermaidEntries = [...regularDeliveryKeys].filter((key) => /mermaid|flowDiagram/i.test(key));
const mermaidCoreChunkBytes = (await stat(join(outputDir, mermaidCoreEntry.file))).size;
const flowchartFiles = without(collectStaticFiles([mermaidComponentKey, mermaidCoreKey, flowchartKey]).files, initial.files);
const flowchartRendererJavaScriptBytes = await totalBytes(flowchartFiles, ".js");

const allFiles = new Set(entries.flatMap(([, entry]) => [entry.file, ...(entry.css ?? []), ...(entry.assets ?? [])]).filter(Boolean));
const allJavaScriptFiles = [...allFiles].filter((file) => extname(file) === ".js");
const allJavaScriptSizes = await Promise.all(allJavaScriptFiles.map(async (file) => ({ file, bytes: (await stat(join(outputDir, file))).size })));
const regularJavaScriptSizes = await Promise.all([...regularDeliveryFiles]
  .filter((file) => extname(file) === ".js")
  .map(async (file) => ({ file, bytes: (await stat(join(outputDir, file))).size })));
const largestRegularChunk = regularJavaScriptSizes.reduce((largest, asset) => asset.bytes > largest.bytes ? asset : largest, { file: "", bytes: 0 });
const totalArtifactJavaScriptBytes = allJavaScriptSizes.reduce((total, asset) => total + asset.bytes, 0);
const totalCssBytes = await totalBytes(allFiles, ".css");

const failures = [];
if (initialJavaScriptBytes > limits.initialJavaScriptBytes) failures.push(`initial JS is ${formatKiB(initialJavaScriptBytes)}`);
if (largestRoute.bytes > limits.maxRouteJavaScriptBytes) failures.push(`route ${largestRoute.key} adds ${formatKiB(largestRoute.bytes)}`);
if (largestRegularChunk.bytes > limits.maxRegularChunkBytes) failures.push(`regular chunk ${largestRegularChunk.file} is ${formatKiB(largestRegularChunk.bytes)}`);
if (formulaRendererJavaScriptBytes > limits.formulaRendererJavaScriptBytes) failures.push(`formula renderer JS is ${formatKiB(formulaRendererJavaScriptBytes)}`);
if (mermaidCoreChunkBytes > limits.mermaidCoreChunkBytes) failures.push(`Mermaid core chunk is ${formatKiB(mermaidCoreChunkBytes)}`);
if (flowchartRendererJavaScriptBytes > limits.flowchartRendererJavaScriptBytes) failures.push(`flowchart renderer JS is ${formatKiB(flowchartRendererJavaScriptBytes)}`);
if (regularMermaidEntries.length) failures.push(`Mermaid leaked into regular delivery: ${regularMermaidEntries.join(", ")}`);
if (totalCssBytes > limits.totalCssBytes) failures.push(`total CSS is ${formatKiB(totalCssBytes)}`);

if (failures.length) throw new Error(`Bundle budget exceeded: ${failures.join("; ")}`);

console.log([
  "Bundle budget passed:",
  `initial ${formatKiB(initialJavaScriptBytes)}`,
  `largest route ${formatKiB(largestRoute.bytes)} (${largestRoute.key})`,
  `formula renderer ${formatKiB(formulaRendererJavaScriptBytes)}`,
  `flowchart renderer ${formatKiB(flowchartRendererJavaScriptBytes)} (core chunk ${formatKiB(mermaidCoreChunkBytes)})`,
  `total CSS ${formatKiB(totalCssBytes)}`
].join(" "));

if (totalArtifactJavaScriptBytes > limits.totalArtifactJavaScriptWarningBytes) {
  console.warn(`Bundle trend warning: emitted JS artifacts total ${formatKiB(totalArtifactJavaScriptBytes)}. This does not represent one user navigation path; review the manifest when adding optional renderers.`);
}
