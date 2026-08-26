import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "C:/Users/pione/Downloads/思政资源图谱导入模板.xlsx";
const outputPath = "C:/Users/pione/Downloads/思政资源图谱导入测试模板.xlsx";
const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);
const resourceSheet = workbook.worksheets.getItem("资源");

console.log((await workbook.inspect({
  kind: "table",
  range: "资源!A1:W5",
  include: "values",
  tableMaxRows: 5,
  tableMaxCols: 23,
})).ndjson);

const resources = [
  [
    "TEST_RES_XBP_001", "西柏坡中共中央旧址", "革命遗址", "河北省", "石家庄市", "平山县", "西柏坡镇",
    "河北省石家庄市平山县西柏坡镇西柏坡村", 113.940798, 38.341077,
    "1948年至1949年间，中共中央在西柏坡指挥了决定中国命运的三大战役。",
    "可用于党史教育、理想信念教育和红色研学。", "https://www.xibaipo.gov.cn/", "小学高年级/初中/高中",
    "革命旧址", "西柏坡景区", "", "08:30-17:00", "否", 120,
    "开展红色故事讲解、研学路线设计或主题班会。", "山区活动需注意集体组织与交通安全。", "",
  ],
  [
    "TEST_RES_XBP_002", "西柏坡纪念馆", "纪念馆", "河北省", "石家庄市", "平山县", "西柏坡镇",
    "河北省石家庄市平山县西柏坡镇", 113.944862, 38.339848,
    "馆内展陈中共中央在西柏坡时期的重要历史文献、图片与实物。",
    "适合开展场馆式思政教育、文献观察和主题研学。", "https://www.xibaipo.gov.cn/", "小学高年级/初中/高中",
    "纪念馆", "西柏坡纪念馆", "", "09:00-17:00", "否", 90,
    "组织讲解参观、研学打卡或展陈观察记录。", "集体参观前请确认开放安排。", "",
  ],
  [
    "TEST_RES_LYS_001", "狼牙山五壮士纪念地", "革命遗址", "河北省", "保定市", "易县", "狼牙山镇",
    "河北省保定市易县狼牙山景区", 115.4448, 39.4054,
    "狼牙山五壮士纪念地承载抗日战争时期的英雄故事。",
    "可用于抗战精神、英勇担当和集体主义教育。", "https://www.hebei.gov.cn/", "小学高年级/初中",
    "抗战遗址", "狼牙山景区", "", "08:00-17:30", "否", 180,
    "开展抗战主题研学、英雄故事分享或路线式教育活动。", "山区路段较多，需重视行进安全。", "",
  ],
];

resourceSheet.getRange("A2:W4").values = resources;
resourceSheet.getRange("A2:W4").format = {
  fill: "#FFF9A6",
  font: { color: "#713F12" },
};
resourceSheet.getRange("A2:A4").format.font = { bold: true, color: "#92400E" };

const preview = await workbook.render({ sheetName: "资源", range: "A1:W5", scale: 1.15, format: "png" });
await fs.writeFile("D:/大学生创新创业/.codex-tmp/catalog-template-edit/hebei-resource-preview.png", new Uint8Array(await preview.arrayBuffer()));

const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
console.log((await workbook.inspect({
  kind: "table",
  range: "资源!A1:W4",
  include: "values",
  tableMaxRows: 4,
  tableMaxCols: 23,
})).ndjson);
