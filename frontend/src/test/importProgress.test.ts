import { describe, expect, it } from "vitest";
import { importActiveStageIndex, importStageState, importStageSummary, processingStages } from "../utils/importProgress";

describe("import progress presentation", () => {
  it("marks extraction as the active step and explains the work", () => {
    expect(importActiveStageIndex("EXTRACTING", "EXTRACTING")).toBe(2);
    expect(importStageState("EXTRACTING", "EXTRACTING", 0)).toBe("completed");
    expect(importStageState("EXTRACTING", "EXTRACTING", 2)).toBe("active");
    expect(importStageSummary("EXTRACTING", "EXTRACTING")).toBe("正在提取文件中的文本与结构。");
  });

  it("completes every visual step only for a finished import", () => {
    for (let index = 0; index < processingStages.length; index++) {
      expect(importStageState("READY", "VALIDATING", index)).toBe("completed");
    }
    expect(importStageSummary("READY", "VALIDATING")).toContain("可生成可编辑草稿");
  });

  it("shows the failed stage without presenting later stages as complete", () => {
    expect(importStageState("FAILED", "NORMALIZING", 2)).toBe("completed");
    expect(importStageState("FAILED", "NORMALIZING", 3)).toBe("failed");
    expect(importStageState("FAILED", "NORMALIZING", 4)).toBe("pending");
  });
});
