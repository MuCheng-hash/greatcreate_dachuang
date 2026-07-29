import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "D:/Projects/greatcreate_dachuang2/outputs/019fabd9-5322-7f30-8c5b-7e92f440be05/石家庄红色文化原型资源首批数据.xlsx";
const outputPath = "D:/Projects/greatcreate_dachuang2/outputs/019fabd9-5322-7f30-8c5b-7e92f440be05/石家庄红色文化原型资源首批数据-高德坐标版.xlsx";

const rows = [
  ["L001","西柏坡中共中央旧址","西柏坡中共中央旧址","B013705X0E",113.940798,38.341077,"GCJ-02","平山县","西柏坡镇西柏坡村","建筑/旧址级","高","可直接用于原型","西柏坡中共中央旧址","2026-07-29"],
  ["L002","西柏坡纪念馆","西柏坡纪念馆","B01370T0XJ",113.944862,38.339848,"GCJ-02","平山县","西柏坡镇","场馆级","高","可直接用于原型","西柏坡纪念馆","2026-07-29"],
  ["L003","西柏坡陈列展览馆","西柏坡陈列展览馆","B0FFG23WL3",113.944878,38.339794,"GCJ-02","平山县","西柏坡镇西柏坡纪念馆","场馆级","高","可直接用于原型","西柏坡陈列展览馆","2026-07-29"],
  ["L004","西柏坡国家安全教育基地","西柏坡国家安全教育馆","B013706H30",113.941893,38.342112,"GCJ-02","平山县","西柏坡镇西柏坡纪念馆西北角","场馆级","高","可用于原型，界面名称建议采用高德名称","西柏坡国家安全教育基地","2026-07-29"],
  ["L005","七届二中全会会址","中国共产党七届二中全会旧址","B0FFF35YGJ",113.940772,38.340775,"GCJ-02","平山县","西柏坡中共中央旧址内南侧","建筑/旧址级","高","可直接用于原型","七届二中全会会址","2026-07-29"],
  ["L006","中央军委作战室旧址","中共中央军委作战室旧址","B0L11RMCOW",113.941634,38.340456,"GCJ-02","平山县","西柏坡中共中央旧址内东南角","建筑/旧址级","高","可直接用于原型","中央军委作战室旧址","2026-07-29"],
  ["L007","中共中央统战部旧址","中央统战部旧址","B01370TCP7",114.008358,38.371146,"GCJ-02","平山县","岗南镇李家庄村","建筑/旧址级","高","可直接用于原型","中共中央统战部旧址 李家庄","2026-07-29"],
  ["L008","全国土地会议相关旧址","西柏坡纪念馆-全国土地会议会址","B0FFF29E88",113.941379,38.338380,"GCJ-02","平山县","西柏坡纪念馆内西南角","建筑/旧址级","高","建议将标准名称改为“全国土地会议会址”","全国土地会议旧址 西柏坡","2026-07-29"],
  ["L009","西柏坡纪念碑","西柏坡纪念碑","B013705X0D",113.943603,38.341272,"GCJ-02","平山县","西柏坡纪念馆西北角","设施级","高","可直接用于原型","西柏坡纪念碑","2026-07-29"],
  ["L010","华北军区烈士陵园","华北军区烈士陵园","B0KG2CXX7G",114.464927,38.045285,"GCJ-02","桥西区","中山西路与师范街交叉口西60米","场所级","高","可直接用于原型","华北军区烈士陵园","2026-07-29"],
  ["L011","石家庄解放纪念馆","石家庄解放纪念馆","B0JDB7GP5X",114.490003,38.041759,"GCJ-02","桥西区","解放广场","场馆级","高","可直接用于原型","石家庄解放纪念馆","2026-07-29"],
  ["L012","石家庄解放纪念碑","石家庄解放纪念碑","B013704EEY",114.489708,38.045625,"GCJ-02","新华区","公里街29号","设施级","高","可直接用于原型；与纪念馆不在同一坐标","石家庄解放纪念碑","2026-07-29"],
  ["L013","中国人民银行成立旧址纪念馆","中国人民银行成立旧址纪念馆","B01370SQ1J",114.477679,38.049788,"GCJ-02","新华区","中华北大街55号","场馆级","高","可直接用于原型","中国人民银行成立旧址纪念馆","2026-07-29"],
  ["L014","正太饭店","正太饭店","B0HRASM6F7",114.490756,38.045084,"GCJ-02","新华区","公里街3号","POI级","中","高德类型为餐饮服务，需确认是否对应历史建筑","正太饭店","2026-07-29"],
  ["L015","沕沕水发电厂旧址","沕沕水生态风景区","B01370705Q",113.752178,38.198371,"GCJ-02","平山县","北冶乡沕沕水村","景区中心","低","只能作为景区定位，不能宣称为发电厂旧址精确坐标","沕沕水发电厂旧址","2026-07-29"],
  ["L016","平山团相关纪念资源","","",null,null,"GCJ-02","平山县","","未定位","无","未匹配到独立纪念馆；建议先作为主题而非地图点","平山团纪念馆","2026-07-29"]
];

const input = await FileBlob.load(inputPath);
const wb = await SpreadsheetFile.importXlsx(input);
const existing = wb.worksheets.getItemOrNullObject ? wb.worksheets.getItemOrNullObject("高德坐标核验") : null;
if (existing && !existing.isNullObject) existing.delete();
const sheet = wb.worksheets.add("高德坐标核验");
const headers = ["地点ID","原标准名称","高德POI名称","高德POI ID","经度","纬度","坐标系","区县","高德地址","坐标精度","匹配置信度","处理建议","查询关键词","查询日期"];
sheet.getRange("A1:N1").values = [headers];
sheet.getRange(`A2:N${rows.length + 1}`).values = rows;
sheet.showGridLines = false;
sheet.getRange("A1:N1").format = {fill:"#B53535",font:{bold:true,color:"#FFFFFF"},horizontalAlignment:"center",verticalAlignment:"center",wrapText:true,borders:{preset:"outside",style:"thin",color:"#D8C7C4"}};
sheet.getRange(`A2:N${rows.length + 1}`).format = {verticalAlignment:"top",wrapText:true,borders:{insideHorizontal:{style:"thin",color:"#E7DDDB"}}};
sheet.getRange(`E2:F${rows.length + 1}`).format.numberFormat = "0.000000";
sheet.getRange("A1:N1").format.rowHeight = 30;
const widths = [10,24,26,16,13,13,11,11,30,14,13,38,28,13];
for (let i=0;i<widths.length;i++) sheet.getRangeByIndexes(0,i,rows.length+1,1).format.columnWidth = widths[i];
sheet.freezePanes.freezeRows(1);
sheet.tables.add(`A1:N${rows.length + 1}`,true,"AmapCoordinateReviewTable");
sheet.getRange(`K2:K${rows.length + 1}`).dataValidation = {rule:{type:"list",values:["高","中","低","无"]}};

const locationSheet = wb.worksheets.getItem("地点");
const locationValues = locationSheet.getRange("A1:N17").values;
const byId = new Map(rows.filter(r => r[4] != null && ["高"].includes(r[10])).map(r => [r[0],r]));
for (let i=1;i<locationValues.length;i++) {
  const match = byId.get(locationValues[i][0]);
  if (match) {
    locationValues[i][6] = match[5];
    locationValues[i][7] = match[4];
    locationValues[i][13] = `${locationValues[i][13] || ""}；高德POI ${match[3]}，GCJ-02，${match[9]}，2026-07-29核验`.replace(/^；/,"");
  }
}
locationSheet.getRange("A1:N17").values = locationValues;
locationSheet.getRange("G2:H17").format.numberFormat = "0.000000";

const coordInspect = await wb.inspect({kind:"table",range:"高德坐标核验!A1:N17",include:"values,formulas",tableMaxRows:20,tableMaxCols:14,maxChars:10000});
const locInspect = await wb.inspect({kind:"table",range:"地点!A1:N17",include:"values,formulas",tableMaxRows:20,tableMaxCols:14,maxChars:10000});
const errors = await wb.inspect({kind:"match",searchTerm:"#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",options:{useRegex:true,maxResults:200},summary:"final formula error scan"});
console.log(coordInspect.ndjson);
console.log(locInspect.ndjson);
console.log(errors.ndjson);

await fs.mkdir(new URL(".", `file:///${outputPath.replace(/\\/g,"/")}`).pathname,{recursive:true}).catch(()=>{});
const out = await SpreadsheetFile.exportXlsx(wb);
await out.save(outputPath);
console.log(JSON.stringify({outputPath,highConfidence:13,medium:1,low:1,unmatched:1}));
