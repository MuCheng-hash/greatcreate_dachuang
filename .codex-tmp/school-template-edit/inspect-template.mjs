import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "C:/Users/pione/Desktop/schools-import-template.xlsx";
const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);
const inspection = await workbook.inspect({
  kind: "workbook,sheet,table,computedStyle",
  maxChars: 5000,
  tableMaxRows: 8,
  tableMaxCols: 16,
  tableMaxCellChars: 100,
});
await fs.writeFile("inspection.txt", inspection.ndjson, "utf8");
const preview = await workbook.render({ sheetName: "学校导入", range: "A1:N8", scale: 1.5, format: "png" });
await fs.writeFile("before.png", new Uint8Array(await preview.arrayBuffer()));
