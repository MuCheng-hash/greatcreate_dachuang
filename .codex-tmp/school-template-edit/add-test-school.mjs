import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const templatePath = "C:/Users/pione/Desktop/schools-import-template.xlsx";
const outputPath = templatePath;
const input = await FileBlob.load(templatePath);
const workbook = await SpreadsheetFile.importXlsx(input);
const sheet = workbook.worksheets.getItem("学校导入");

// Keep the existing example row untouched and append a real school suitable for import testing.
sheet.getRange("A3:N3").copyTo(sheet.getRange("A4:N4"), "all");
sheet.getRange("A4:N4").values = [[
  "平山县东关小学",
  "小学",
  "小学",
  "其他",
  "河北省石家庄市平山县东街",
  "114.2033622",
  "38.2518333",
  "河北省",
  "石家庄市",
  "平山县",
  "",
  "真实学校测试数据，坐标来自 OpenStreetMap 公开地图数据。",
  "",
  "",
]];

const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);

const check = await workbook.inspect({
  kind: "table",
  range: "A1:N4",
  tableMaxRows: 4,
  tableMaxCols: 14,
  maxChars: 3000,
});
await fs.writeFile("after-inspection.txt", check.ndjson, "utf8");
const preview = await workbook.render({ sheetName: "学校导入", range: "A1:N5", scale: 1.5, format: "png" });
await fs.writeFile("after.png", new Uint8Array(await preview.arrayBuffer()));
