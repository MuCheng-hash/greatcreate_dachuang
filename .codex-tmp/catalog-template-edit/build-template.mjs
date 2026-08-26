import fs from "node:fs/promises";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputPath = "D:/大学生创新创业/.codex-tmp/catalog-template-edit/思政资源图谱导入模板-更新版.xlsx";
const workbook = Workbook.create();

const sheetDefinitions = {
  "资源": ["编码", "资源名称", "资源类型", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "经度", "纬度", "简介", "教育价值", "数据来源", "适合学段", "资源子类", "所属机构", "联系电话", "开放时间", "需要预约", "建议时长", "活动建议", "安全提示", "图片URL"],
  "遗址": ["编码", "名称", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "经度", "纬度", "简介", "详情", "图片URL", "来源URL"],
  "纪念馆": ["编码", "名称", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "经度", "纬度", "简介", "详情", "图片URL", "来源URL"],
  "人物": ["编码", "名称", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "简介", "详情", "图片URL", "来源URL"],
  "事件": ["编码", "名称", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "经度", "纬度", "简介", "详情", "图片URL", "来源URL"],
  "故事": ["编码", "名称", "省份名称", "城市名称", "区县名称", "乡镇名称", "地址", "简介", "详情", "图片URL", "来源URL"],
  "关系": ["源实体类型", "源实体编码", "关系类型", "目标实体类型", "目标实体编码", "备注"],
};

for (const [name, headers] of Object.entries(sheetDefinitions)) {
  const sheet = workbook.worksheets.add(name);
  const lastColumn = String.fromCharCode(64 + headers.length);
  sheet.getRange(`A1:${lastColumn}1`).values = [headers];
  sheet.getRange(`A1:${lastColumn}1`).format = {
    fill: "#D8F8D5",
    font: { bold: true, color: "#166534", size: 11 },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    borders: { preset: "all", style: "thin", color: "#B7D9B2" },
  };
  sheet.getRange(`A1:${lastColumn}1`).format.rowHeight = 24;
  sheet.getRange(`A1:${lastColumn}1000`).format.columnWidth = 18;
  sheet.getRange(`A1:${lastColumn}1`).format.columnWidth = 20;
  sheet.freezePanes.freezeRows(1);
  if (name === "资源") {
    sheet.getRange("D1:G1000").format.columnWidth = 16;
    sheet.getRange("H1:H1000").format.columnWidth = 32;
    sheet.getRange("K1:L1000").format.columnWidth = 34;
    sheet.getRange("U1:V1000").format.columnWidth = 34;
    sheet.getRange("C2:C1000").dataValidation = { rule: { type: "list", values: ["革命遗址", "革命英雄", "革命事件", "纪念馆"] } };
    sheet.getRange("S2:S1000").dataValidation = { rule: { type: "list", values: ["是", "否"] } };
  } else if (name === "人物" || name === "故事") {
    sheet.getRange("C1:F1000").format.columnWidth = 16;
    sheet.getRange("G1:G1000").format.columnWidth = 32;
    sheet.getRange("H1:I1000").format.columnWidth = 34;
  } else if (name !== "关系") {
    sheet.getRange("C1:F1000").format.columnWidth = 16;
    sheet.getRange("G1:G1000").format.columnWidth = 32;
    sheet.getRange("J1:K1000").format.columnWidth = 34;
  }
}

const resource = workbook.worksheets.getItem("资源");
resource.getRange("A2:W2").values = [["RES_EXAMPLE_001", "示例革命遗址", "革命遗址", "河北省", "石家庄市", "平山县", "西柏坡镇", "请删除此示例行后再导入", 113.94, 38.34, "示例数据", "示例教育价值", "https://example.com/source", "小学/初中", "革命旧址", "示例机构", "", "08:30-17:00", "否", 60, "示例活动", "注意安全", "https://example.com/image.jpg"]];
resource.getRange("A2:W2").format = { fill: "#FFF9A6", font: { color: "#9A3412" } };
resource.getRange("A1:W1").format.rowHeight = 28;

const relation = workbook.worksheets.getItem("关系");
relation.getRange("A2:F2").values = [["SITE", "SITE_EXAMPLE_001", "OCCURRED_AT", "EVENT", "EVENT_EXAMPLE_001", "示例关系，导入前删除"]];
relation.getRange("A2:F2").format = { fill: "#FFF9A6", font: { color: "#9A3412" } };

const preview = await workbook.render({ sheetName: "资源", range: "A1:W3", scale: 1.2, format: "png" });
await fs.writeFile("D:/大学生创新创业/.codex-tmp/catalog-template-edit/preview.png", new Uint8Array(await preview.arrayBuffer()));
for (const sheetName of ["人物", "故事"]) {
  const entityPreview = await workbook.render({ sheetName, range: "A1:K3", scale: 1.5, format: "png" });
  await fs.writeFile(`D:/大学生创新创业/.codex-tmp/catalog-template-edit/${sheetName}-preview.png`, new Uint8Array(await entityPreview.arrayBuffer()));
}
console.log((await workbook.inspect({ kind: "table", range: "资源!A1:W2", include: "values", tableMaxRows: 2, tableMaxCols: 23 })).ndjson);
console.log((await workbook.inspect({ kind: "table", range: "人物!A1:K1", include: "values", tableMaxRows: 1, tableMaxCols: 11 })).ndjson);
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
