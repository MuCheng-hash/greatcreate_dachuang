import fs from "node:fs/promises";
import path from "node:path";
import { Workbook, SpreadsheetFile } from "@oai/artifact-tool";

const outputDir = "D:/大创项目";
const outputPath = path.join(outputDir, "石家庄乡村学校与思政资源采集模板.xlsx");

const workbook = Workbook.create();

const schoolSheet = workbook.worksheets.add("学校基础信息");
const geoSheet = workbook.worksheets.add("地图定位信息");
const resourceSheet = workbook.worksheets.add("周边思政资源");
const guideSheet = workbook.worksheets.add("填写说明");

function setTitle(sheet, text) {
  const range = sheet.getRange("A1:J1");
  range.merge();
  range.values = [[text]];
  range.format.font.bold = true;
  range.format.font.size = 18;
  range.format.font.color = "#1f3b64";
  range.format.fill.color = "#eaf2ff";
  range.rowHeight = 28;
}

function writeSheet(sheet, title, headers, sampleRows, widths) {
  setTitle(sheet, title);
  const headerRange = sheet.getRangeByIndexes(2, 0, 1, headers.length);
  headerRange.values = [headers];
  headerRange.format.font.bold = true;
  headerRange.format.font.color = "#ffffff";
  headerRange.format.fill.color = "#3a6fb0";
  headerRange.format.horizontalAlignment = "center";
  headerRange.format.verticalAlignment = "center";
  headerRange.rowHeight = 24;

  const bodyRange = sheet.getRangeByIndexes(3, 0, sampleRows.length, headers.length);
  bodyRange.values = sampleRows;
  bodyRange.format.wrapText = true;
  bodyRange.format.verticalAlignment = "top";
}

const schoolHeaders = [
  "school_id",
  "学校名称",
  "学校别名",
  "区县",
  "乡镇/乡",
  "村",
  "学校层次",
  "学校类别",
  "办学性质",
  "是否乡村学校",
  "是否教学点",
  "学校地址",
  "邮编",
  "联系电话",
  "来源文件",
  "来源链接",
  "采集日期",
  "采集人",
  "备注"
];

const schoolRows = [
  [
    "SJZ-GC-0001",
    "石家庄市藁城区常安镇里庄小学",
    "",
    "藁城区",
    "常安镇",
    "里庄村",
    "小学",
    "村小",
    "公办",
    "是",
    "否",
    "河北省石家庄市藁城区常安镇里庄村振兴大街西220号",
    "",
    "",
    "藁城区义务教育学校名录.pdf",
    "https://www.gc.gov.cn/atm/7/20240205161337228.pdf",
    "2026-05-21",
    "",
    "示例行，可删除"
  ],
  [
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    ""
  ]
];

writeSheet(
  schoolSheet,
  "石家庄乡村学校基础信息采集表",
  schoolHeaders,
  schoolRows,
  [110, 230, 130, 100, 110, 110, 90, 110, 90, 100, 100, 320, 80, 110, 180, 240, 100, 90, 180]
);

const geoHeaders = [
  "school_id",
  "学校名称",
  "经度",
  "纬度",
  "坐标来源",
  "POI名称",
  "POI地址",
  "POI类型",
  "匹配置信度",
  "是否人工复核",
  "复核结果",
  "复核人",
  "复核日期",
  "备注"
];

const geoRows = [
  [
    "SJZ-GC-0001",
    "石家庄市藁城区常安镇里庄小学",
    "114.953718",
    "38.027103",
    "高德POI",
    "石家庄市藁城区常安镇里庄小学",
    "河北省石家庄市藁城区常安镇里庄村振兴大街西220号",
    "科教文化服务;学校;小学",
    "高",
    "否",
    "",
    "",
    "",
    "示例行，可删除"
  ],
  ["", "", "", "", "", "", "", "", "", "", "", "", "", ""]
];

writeSheet(
  geoSheet,
  "学校地图定位与坐标复核表",
  geoHeaders,
  geoRows,
  [110, 230, 100, 100, 110, 220, 300, 170, 100, 100, 100, 90, 100, 180]
);

const resourceHeaders = [
  "resource_id",
  "school_id",
  "学校名称",
  "资源名称",
  "资源大类",
  "资源子类",
  "资源地址",
  "经度",
  "纬度",
  "距离学校米数",
  "所属区县",
  "所属乡镇",
  "教育价值说明",
  "可开展活动",
  "适合学段",
  "来源",
  "是否已复核",
  "备注"
];

const resourceRows = [
  [
    "RES-SJZ-0001",
    "SJZ-GC-0001",
    "石家庄市藁城区常安镇里庄小学",
    "常安镇某养老院",
    "公益实践",
    "养老院",
    "河北省石家庄市藁城区常安镇示例地址",
    "",
    "",
    "1800",
    "藁城区",
    "常安镇",
    "可用于开展敬老爱老、社会责任与志愿服务教育",
    "敬老志愿服务、劳动实践、节日慰问",
    "小学高年级/初中",
    "地图采集+人工核验",
    "否",
    "示例行，可删除"
  ],
  [
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    ""
  ]
];

writeSheet(
  resourceSheet,
  "学校周边思政资源采集表",
  resourceHeaders,
  resourceRows,
  [120, 110, 220, 220, 110, 110, 280, 95, 95, 110, 100, 100, 220, 220, 120, 160, 100, 180]
);

setTitle(guideSheet, "填写说明与建议口径");
const guideHeaders = ["模块", "字段/主题", "填写建议"];
const guideRows = [
  ["学校基础信息", "是否乡村学校", "建议优先纳入名称或地址中含镇、乡、村的学校，也可结合区县教育局口径确认。"],
  ["学校基础信息", "学校类别", "建议统一使用：中心小学、村小、教学点、九年一贯制学校、寄宿制学校。"],
  ["地图定位信息", "坐标来源", "建议统一使用：高德POI、手工定位、学校官网、卫星图校正。"],
  ["地图定位信息", "匹配置信度", "建议统一使用：高、中、低。对于重名学校必须人工复核。"],
  ["周边思政资源", "资源大类", "建议统一使用：红色文化、非遗传统文化、地方历史文化、公益实践、劳动教育、公共文化、生态文明教育。"],
  ["周边思政资源", "教育价值说明", "重点说明资源如何进入课堂，如何服务爱国主义教育、文化认同教育、劳动教育、志愿服务教育。"],
  ["周边思政资源", "可开展活动", "尽量写成可执行活动，如主题班会、研学走访、志愿服务、非遗体验、劳动实践等。"]
];

const guideHeaderRange = guideSheet.getRange("A3:C3");
guideHeaderRange.values = [guideHeaders];
guideHeaderRange.format.font.bold = true;
guideHeaderRange.format.font.color = "#1f3b64";
guideHeaderRange.format.fill.color = "#dde9f8";
guideHeaderRange.rowHeight = 24;
const guideBodyRange = guideSheet.getRangeByIndexes(3, 0, guideRows.length, guideHeaders.length);
guideBodyRange.values = guideRows;
guideBodyRange.format.wrapText = true;
guideBodyRange.format.verticalAlignment = "top";
await fs.mkdir(outputDir, { recursive: true });
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);

console.log(outputPath);
