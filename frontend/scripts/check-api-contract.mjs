import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {parse} from "yaml";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const projectRoot = path.resolve(frontendRoot, "..");
const openApiPath = path.join(projectRoot, "docs", "api", "openapi.yaml");
const document = parse(fs.readFileSync(openApiPath, "utf8"));
const failures = [];
const httpMethods = new Set(["get", "post", "put", "patch", "delete"]);

const specEndpoints = new Set();
for (const [route, pathItem] of Object.entries(document.paths ?? {})) {
  for (const method of Object.keys(pathItem)) {
    if (!httpMethods.has(method)) continue;
    specEndpoints.add(`${method.toUpperCase()} ${route}`);
    const responses = pathItem[method].responses ?? {};
    if (responses.default?.$ref !== "#/components/responses/Problem") {
      failures.push(`${method.toUpperCase()} ${route} does not declare the shared Problem response`);
    }
  }
}

const javaEndpoints = new Set();
const javaRoot = path.join(projectRoot, "src", "main", "java");
for (const file of walk(javaRoot).filter((candidate) => candidate.endsWith("Controller.java"))) {
  const source = fs.readFileSync(file, "utf8");
  const classIndex = source.search(/\bclass\s+\w+/);
  const classPrefixMatches = [...source.slice(0, classIndex).matchAll(/@RequestMapping\("([^"]+)"\)/g)];
  const classPrefix = classPrefixMatches.at(-1)?.[1] ?? "";
  const mappingPattern = /@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)(?:\("([^"]*)"\))?/g;
  for (const match of source.slice(classIndex).matchAll(mappingPattern)) {
    const method = match[1].replace("Mapping", "").toUpperCase();
    const route = normalizeRoute(classPrefix + (match[2] ?? ""));
    if (route.startsWith("/api/")) javaEndpoints.add(`${method} ${route.slice(4)}`);
  }
}
compareSets("Java controllers", javaEndpoints, "OpenAPI", specEndpoints);

if (document.components?.responses?.Json) failures.push("The generic Json response must not be reintroduced");
if (!document.components?.schemas?.Problem) failures.push("Problem schema is missing");

const tsSource = fs.readFileSync(path.join(frontendRoot, "src", "types", "api.ts"), "utf8");
const javaContract = fs.readFileSync(path.join(javaRoot, "com", "example", "interviewreader", "document", "ApiContractValues.java"), "utf8");
for (const [constantName, schemaName] of [
  ["SOURCE_TYPES", "SourceType"],
  ["NODE_TYPES", "NodeType"],
  ["BLOCK_TYPES", "BlockType"],
  ["SEMANTIC_ROLES", "SemanticRole"],
  ["MASTERY_STATES", "MasteryState"],
  ["VERSION_STATUSES", "VersionStatus"],
  ["IMPORT_STATUSES", "ImportStatus"],
  ["IMPORT_STAGES", "ImportStage"],
  ["IMPORT_RESOLUTIONS", "ImportResolution"],
  ["IMPORT_ISSUE_SEVERITIES", "ImportIssueSeverity"]
]) {
  const schemaValues = new Set(document.components.schemas[schemaName]?.enum ?? []);
  compareSets(`frontend ${constantName}`, new Set(readTsArray(tsSource, constantName)), `OpenAPI ${schemaName}`, schemaValues);
  compareSets(`backend ${constantName}`, new Set(readJavaSet(javaContract, constantName)), `OpenAPI ${schemaName}`, schemaValues);
}

const interfaceSchemas = {
  AuthSession: "AuthSession", DocumentSummary: "DocumentSummary", DocumentListResponse: "DocumentPage",
  TocNode: "TocNode", ContentBlock: "ContentBlock", NodeContent: "NodeContent", SearchHit: "SearchHit",
  ReadingProgress: "ReadingProgress", ImportJob: "ImportJob", ImportIssue: "ImportIssue",
  ExistingDocumentMatch: "ExistingDocumentMatch", ImportDocumentPreview: "ImportDocumentPreview", DocumentMetadata: "DocumentMetadata", DocumentVersion: "DocumentVersion",
  StagedSection: "DocumentPackageSection", StagedBlock: "DocumentPackageBlock", DocumentInfo: "DocumentInfo",
  VersionInfo: "VersionInfo", AssetInfo: "AssetInfo", DocumentPackage: "DocumentPackage",
  VersionSummary: "VersionSummary", EditableVersion: "EditableVersion", AdminDocumentSummary: "AdminDocumentSummary",
  AdminDocumentPage: "AdminDocumentPage", EditorDocument: "EditorDocument", EditorNode: "EditorNode",
  EditorSnapshot: "EditorSnapshot", EditorBlock: "EditorBlock", NodeBlocksPage: "NodeBlocksPage",
  StructureNode: "StructureNode", BlockMutationResult: "BlockMutationResult", ImageBlockUploadResult: "ImageBlockUploadResult"
};
for (const [interfaceName, schemaName] of Object.entries(interfaceSchemas)) {
  const fields = readTsInterface(tsSource, interfaceName);
  const schema = document.components.schemas[schemaName];
  if (!schema) {
    failures.push(`OpenAPI schema ${schemaName} is missing`);
    continue;
  }
  compareSets(`frontend ${interfaceName} fields`, new Set(fields.keys()), `OpenAPI ${schemaName} fields`, new Set(Object.keys(schema.properties ?? {})));
  const required = new Set(schema.required ?? []);
  const frontendRequired = new Set([...fields].filter(([, optional]) => !optional).map(([name]) => name));
  compareSets(`frontend ${interfaceName} required fields`, frontendRequired, `OpenAPI ${schemaName} required fields`, required);
}

const responsiveSource = fs.readFileSync(path.join(frontendRoot, "src", "shared", "responsive.ts"), "utf8");
const mobileWidth = Number(responsiveSource.match(/ADMIN_MOBILE_MAX_WIDTH = (\d+)/)?.[1]);
const styles = fs.readFileSync(path.join(frontendRoot, "src", "styles.css"), "utf8");
if (!styles.includes(`@media (max-width: ${mobileWidth}px)`)) failures.push("CSS mobile breakpoint differs from responsive.ts");
if (!styles.includes(`(min-width: ${mobileWidth + 1}px)`)) failures.push("CSS desktop breakpoint differs from responsive.ts");

const applicationYaml = fs.readFileSync(path.join(projectRoot, "src", "main", "resources", "application.yml"), "utf8");
const applicationConfig = parse(applicationYaml);
const nginx = fs.readFileSync(path.join(projectRoot, "deploy", "nginx", "interview-reader.conf.example"), "utf8");
const runtimeConfig = fs.readFileSync(path.join(frontendRoot, "src", "shared", "runtimeConfig.ts"), "utf8");
const readme = fs.readFileSync(path.join(projectRoot, "README.md"), "utf8");

const backendPort = Number(readEnvironmentDefault(applicationConfig.server?.port, "SERVER_PORT"));
const frontendPort = Number(runtimeConfig.match(/DEFAULT_API_PROXY_TARGET = "http:\/\/[^:"]+:(\d+)"/)?.[1]);
const nginxPort = Number(nginx.match(/server 127\.0\.0\.1:(\d+);/)?.[1]);
const documentedPorts = [...readme.matchAll(/http:\/\/localhost:(\d+)/g)].map((match) => Number(match[1]));
if (!backendPort || frontendPort !== backendPort || nginxPort !== backendPort
    || documentedPorts.some((port) => port !== backendPort)) {
  failures.push("Backend port default differs across Spring, Vite, Nginx, or README");
}

const uploadDefault = readEnvironmentDefault(applicationConfig["interview-reader"]?.upload?.["max-size"], "UPLOAD_MAX_SIZE");
const nginxUpload = nginx.match(/client_max_body_size\s+([^;]+);/)?.[1];
if (!uploadDefault || !nginxUpload || sizeInBytes(uploadDefault) !== sizeInBytes(nginxUpload)) {
  failures.push("Spring and Nginx upload limits differ");
}
const sharedUploadProperty = "$" + "{interview-reader.upload.max-size}";
if (applicationConfig.spring?.servlet?.multipart?.["max-file-size"] !== sharedUploadProperty
    || applicationConfig.spring?.servlet?.multipart?.["max-request-size"] !== sharedUploadProperty) {
  failures.push("Spring multipart limits must reference interview-reader.upload.max-size");
}

if (failures.length) {
  console.error(failures.map((failure) => `- ${failure}`).join("\n"));
  process.exit(1);
}
console.log(`Contract check passed: ${specEndpoints.size} endpoints, concrete schemas, aligned enums and operational limits.`);

function readEnvironmentDefault(value, variableName) {
  if (typeof value !== "string") return null;
  const match = value.match(new RegExp("^\\$\\{" + variableName + ":(.+)\\}$"));
  return match?.[1] ?? null;
}
function sizeInBytes(value) {
  const match = String(value).trim().match(/^(\d+)\s*(b|kb|kib|m|mb|mib)$/i);
  if (!match) return NaN;
  const unit = match[2].toLowerCase();
  const multiplier = unit === "b" ? 1 : ["kb", "kib"].includes(unit) ? 1024 : 1024 * 1024;
  return Number(match[1]) * multiplier;
}
function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const candidate = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(candidate) : [candidate];
  });
}
function normalizeRoute(route) {
  const normalized = route.replace(/\/+/g, "/");
  return normalized.length > 1 && normalized.endsWith("/") ? normalized.slice(0, -1) : normalized;
}
function compareSets(leftName, left, rightName, right) {
  const missing = [...right].filter((value) => !left.has(value));
  const extra = [...left].filter((value) => !right.has(value));
  if (missing.length || extra.length) failures.push(`${leftName} differs from ${rightName}; missing=[${missing}], extra=[${extra}]`);
}
function readTsArray(source, name) {
  const body = source.match(new RegExp(`export const ${name} = \\[([^\\]]+)\\] as const`))?.[1];
  if (!body) throw new Error(`Cannot read TypeScript constant ${name}`);
  return [...body.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}
function readJavaSet(source, name) {
  const assignmentStart = source.indexOf(`${name} =`);
  const assignmentEnd = source.indexOf(";", assignmentStart);
  if (assignmentStart < 0 || assignmentEnd < 0) throw new Error(`Cannot read Java constant ${name}`);
  const assignment = source.slice(assignmentStart, assignmentEnd + 1);
  const literalValues = [...assignment.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  if (literalValues.length) return literalValues;

  const enumName = assignment.match(/=\s*(\w+)\.codes\(\)/)?.[1];
  if (!enumName) throw new Error(`Cannot read Java constant ${name}`);
  const enumFile = walk(javaRoot).find((candidate) => candidate.endsWith(`${path.sep}${enumName}.java`));
  if (!enumFile) throw new Error(`Cannot find Java enum ${enumName}`);
  const enumSource = fs.readFileSync(enumFile, "utf8");
  const enumStart = enumSource.indexOf(`enum ${enumName}`);
  const constantsStart = enumSource.indexOf("{", enumStart) + 1;
  const constantsEnd = enumSource.indexOf(";", constantsStart);
  if (enumStart < 0 || constantsStart <= 0 || constantsEnd < 0) throw new Error(`Cannot read Java enum ${enumName}`);
  return enumSource.slice(constantsStart, constantsEnd)
      .split(/\r?\n/)
      .map((line) => line.match(/^\s*([A-Z][A-Z0-9_]*)\s*(?:\(\s*"([^"]+)"[^)]*\))?\s*,?\s*$/))
      .filter(Boolean)
      .map((match) => match[2] ?? match[1]);
}
function readTsInterface(source, name) {
  const body = source.match(new RegExp(`export interface ${name} \\{([^}]*)\\}`))?.[1];
  if (!body) throw new Error(`Cannot read TypeScript interface ${name}`);
  return new Map([...body.matchAll(/(?:^|;)\s*([A-Za-z][A-Za-z0-9]*)(\?)?:/g)].map((match) => [match[1], Boolean(match[2])]));
}
