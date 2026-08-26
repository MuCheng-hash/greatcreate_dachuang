import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const desktopTemplate = "C:/Users/pione/Desktop/schools-import-template.xlsx";
const updatedTemplate = "D:/大学生创新创业/.codex-tmp/school-template-edit/schools-import-template-no-school-level.xlsx";
const workDir = "D:/大学生创新创业/.codex-tmp/school-template-edit";

const oldBlob = await FileBlob.load(desktopTemplate);
const oldWorkbook = await SpreadsheetFile.importXlsx(oldBlob);
console.log((await oldWorkbook.inspect({
  kind: "table",
  range: "学校导入!A1:N3",
  include: "values",
  tableMaxRows: 3,
  tableMaxCols: 14,
})).ndjson);
const oldPreview = await oldWorkbook.render({ sheetName: "学校导入", range: "A1:N4", scale: 1.5, format: "png" });
await fs.writeFile(`${workDir}/before.png`, new Uint8Array(await oldPreview.arrayBuffer()));

const workbook = Workbook.create();
const sheet = workbook.worksheets.add("学校导入");
sheet.showGridLines = true;

const headers = [
  "学校名称", "学校类型", "办学性质", "学校地址", "经度", "纬度", "省份名称",
  "城市名称", "区县名称", "乡镇名称", "学校简介", "联系电话", "负责人姓名",
];
const example = [
  "平山县西柏坡希望小学", "乡镇中心小学", "公办", "河北省石家庄市平山县西柏坡镇迎宾路7号",
  113.939, 38.348438, "河北省", "石家庄市", "平山县", "西柏坡镇",
  "用于红色文化资源密集区域学校试点。", "", "",
];

sheet.getRange("A1:M1").values = [headers];
sheet.getRange("A2:M2").merge();
sheet.getRange("A2").values = [["示例数据：导入前请删除下一行的“平山县西柏坡希望小学”示例信息。"]];
sheet.getRange("A3:M3").values = [example];
sheet.getRange("A1:M1").format = {
  fill: "#D8F8D5",
  font: { bold: true, color: "#166534", size: 12 },
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#B7D9B2" },
};
sheet.getRange("A2:M2").format = {
  fill: "#FFF9A6",
  font: { bold: true, color: "#9A3412", size: 12 },
  horizontalAlignment: "left",
  verticalAlignment: "center",
};
sheet.getRange("A3:M3").format = {
  borders: { preset: "all", style: "thin", color: "#E5E7EB" },
  verticalAlignment: "center",
};
sheet.getRange("E3:F1000").format.numberFormat = "0.0000000";
sheet.getRange("B3:B1000").dataValidation = { rule: { type: "list", values: ["乡镇中心小学", "村小"] } };
sheet.getRange("C3:C1000").dataValidation = { rule: { type: "list", values: ["公办", "民办", "其他"] } };
sheet.getRange("A1:A1000").format.columnWidth = 23;
sheet.getRange("B1:B1000").format.columnWidth = 18;
sheet.getRange("C1:C1000").format.columnWidth = 14;
sheet.getRange("D1:D1000").format.columnWidth = 34;
sheet.getRange("E1:F1000").format.columnWidth = 14;
sheet.getRange("G1:J1000").format.columnWidth = 16;
sheet.getRange("K1:K1000").format.columnWidth = 34;
sheet.getRange("L1:M1000").format.columnWidth = 16;
sheet.getRange("A1:M1").format.rowHeight = 24;
sheet.getRange("A2:M2").format.rowHeight = 28;
sheet.freezePanes.freezeRows(1);

console.log((await workbook.inspect({
  kind: "table",
  range: "学校导入!A1:M3",
  include: "values",
  tableMaxRows: 3,
  tableMaxCols: 13,
})).ndjson);
const preview = await workbook.render({ sheetName: "学校导入", range: "A1:M4", scale: 1.5, format: "png" });
await fs.writeFile(`${workDir}/after.png`, new Uint8Array(await preview.arrayBuffer()));
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(updatedTemplate);
