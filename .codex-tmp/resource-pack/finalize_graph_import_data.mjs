import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "D:/Projects/greatcreate_dachuang2/outputs/019fabd9-5322-7f30-8c5b-7e92f440be05/石家庄红色文化原型资源首批数据-高德坐标版.xlsx";
const outputPath = "D:/Projects/greatcreate_dachuang2/outputs/019fabd9-5322-7f30-8c5b-7e92f440be05/石家庄红色文化知识图谱-导入准备版.xlsx";

const input = await FileBlob.load(inputPath);
const wb = await SpreadsheetFile.importXlsx(input);
const initial = await wb.inspect({kind:"workbook,sheet,table",maxChars:6000,tableMaxRows:4,tableMaxCols:8,tableMaxCellChars:80});
console.log(initial.ndjson);

const newSources = [
  ["S025","中共中央旧址","场馆独立详情页","西柏坡纪念馆","http://www.xbpjng.cn/columns/05e5e36a-cf36-4690-b40d-f49d993d047a/202406/05/298579bf-d9d8-404a-9101-df9f48697105.html","一级","西柏坡中共中央旧址史实","2026-07-29","有效","正式名称、旧址构成、历史背景","实体级"],
  ["S026","西柏坡纪念馆简介","场馆独立详情页","西柏坡纪念馆","http://www.xbpjng.cn/columns/baa02651-4003-4f69-8f0e-4e1cf8cc98ea/index.html","一级","纪念馆基本信息","2026-07-29","有效","场馆性质、建设历史、展览概况","实体级"],
  ["S027","国家安全教育馆","场馆独立详情页","西柏坡纪念馆","https://www.xbpjng.cn/columns/cf1e6ca7-b993-491a-9f87-0ae4a101abfa/index.html","一级","国家安全教育馆信息","2026-07-29","有效","场馆正式名称、展览主题","实体级"],
  ["S028","中国共产党七届二中全会会址","场馆独立详情页","西柏坡纪念馆","http://www.xbpjng.cn/columns/05e5e36a-cf36-4690-b40d-f49d993d047a/202406/05/2ce10a64-dc01-4472-890e-5a539c5eba2d.html","一级","七届二中全会会址史实","2026-07-29","有效","正式名称、会址、会议史实","实体级"],
  ["S029","中央军委作战室","场馆独立详情页","西柏坡纪念馆","http://www.xbpjng.cn/columns/05e5e36a-cf36-4690-b40d-f49d993d047a/202406/05/c4dd2f32-12dc-4ec9-a42a-adb9ba7f4ebb.html","一级","中央军委作战室史实","2026-07-29","有效","建筑构成、职责、战役指挥关系","实体级"],
  ["S030","李家庄中央统战部旧址获评省级模范集体","政府报道","平山县人民政府","http://www.sjzps.gov.cn/columns/2ae4866e-4d0c-420f-81d5-98a946074dc8/202512/19/c41a4b8c-1859-4e06-b1eb-9823d36a2a8f.html","一级","统战部旧址存在及主体信息","2026-07-29","有效","正式名称、所在地区、场馆主体","实体级报道"],
  ["S031","解放纪念碑","政府独立详情页","石家庄市人民政府","https://www.sjz.gov.cn/columns/9fbd4f34-f122-474f-a646-6b2584bc9c4d/202008/14/83e5f494-5eb7-4c72-b740-f1ea77211d1c.html","一级","石家庄解放纪念碑史实","2026-07-29","有效","名称、历史、城市意义","实体级"],
  ["S032","华北军区烈士陵园","政府独立详情页","石家庄市人民政府","https://www.sjz.gov.cn/columns/aee01a7b-4f7d-42b0-a53b-8c8029713e07/202509/25/c8a15f50-3b92-4e08-8807-74d585d676bd.html","一级","华北军区烈士陵园史实","2026-07-29","有效","名称、位置、历史、纪念对象","实体级"],
  ["S033","石家庄正太饭店对外开放","政府报道","石家庄市人民政府","https://www.sjz.gov.cn/columns/08733b77-0f89-4906-8fab-32809309a889/202211/12/279e10d6-c730-4f19-a18d-52c06deb3fae.html","一级","正太饭店建筑与开放信息","2026-07-29","有效","建筑身份、开放情况、历史价值","实体级报道"],
  ["S034","沕沕水","政府资源详情页","石家庄市人民政府","https://www.sjz.gov.cn/columns/aee01a7b-4f7d-42b0-a53b-8c8029713e07/202509/25/1bbb6cd9-54cf-4e7a-a378-b86c6255cbc6.html","一级","沕沕水红色资源概况","2026-07-29","有效","地点、红色旅游资源概况","景区级"],
  ["S035","人民城市·幸福图景城市主题展引发热烈反响","政府报道","石家庄市人民政府","https://www.sjz.gov.cn/columns/839b1981-330e-4901-aba5-6c392154ce03/202607/20/28b8408b-9fb6-4a5a-9fd6-75e06dcff862.html","一级","石家庄解放纪念馆存在与活动","2026-07-29","有效","场馆存在、活动信息","实体级报道"],
  ["S036","元旦假期趣游石家庄","政府文旅报道","石家庄市人民政府","https://www.sjz.gov.cn/columns/8e2f0b12-4574-4ced-b339-bcb378d54b2d/202512/30/0cbc2257-4c68-4d31-ace9-c8b868610e23.html","一级","中国人民银行成立旧址参观资源","2026-07-29","有效","官方推荐参观资源、场馆存在","目录/报道级"],
  ["S037","华北战局与中共中央移驻西柏坡","场馆研究文章","西柏坡纪念馆","https://www.xbpjng.cn/columns/7766e098-438e-4936-873e-7f258d98c222/202406/05/dee0e983-704b-41e4-911a-258f92f8c2bf.html","一级","全国土地会议及中共中央移驻背景","2026-07-29","有效","历史背景、相关会议脉络","主题级"]
];

const officialLocationSources = {
  L001:["S025"], L002:["S026"], L003:["S002"], L004:["S027"],
  L005:["S028"], L006:["S029"], L007:["S030"], L008:["S037"],
  L009:["S001","S022"], L010:["S032"], L011:["S035"], L012:["S031"],
  L013:["S009","S036"], L014:["S033"], L015:["S034"], L016:["S011"]
};

const nameUpdates = {
  L004:"西柏坡国家安全教育馆",
  L006:"中共中央军委作战室旧址",
  L008:"全国土地会议会址"
};

const locationSheet = wb.worksheets.getItem("地点");
const locationRows = locationSheet.getRange("A1:N17").values;
for (let i=1;i<locationRows.length;i++) {
  const id = locationRows[i][0];
  if (nameUpdates[id]) locationRows[i][1] = nameUpdates[id];
  if (id === "L014") {
    locationRows[i][12] = "待精确核验";
    locationRows[i][13] = `${locationRows[i][13]}；图谱状态STAGING，高德POI与历史建筑需人工确认`;
  }
  if (id === "L015") {
    locationRows[i][12] = "待精确核验";
    locationRows[i][13] = `${locationRows[i][13]}；图谱状态STAGING，当前仅有景区中心坐标`;
  }
  if (id === "L016") {
    locationRows[i][2] = "历史主题候选（不导入Location）";
    locationRows[i][12] = "线索待核";
    locationRows[i][13] = `${locationRows[i][13]}；图谱状态EXCLUDED，可后续转为Organization或HistoricalTopic`;
  }
}
locationSheet.getRange("A1:N17").values = locationRows;

const amapSheet = wb.worksheets.getItem("高德坐标核验");
const amapRows = amapSheet.getRange("A1:N17").values;
for (let i=1;i<amapRows.length;i++) if (nameUpdates[amapRows[i][0]]) amapRows[i][1] = nameUpdates[amapRows[i][0]];
amapSheet.getRange("A1:N17").values = amapRows;

const sourceOriginal = wb.worksheets.getItem("来源").getRange("A1:I25").values;
const sourceMasterRows = sourceOriginal.slice(1).map(r => [...r,"",""]).concat(newSources);

const styleSheet = (sheet, headers, rows, widths) => {
  const letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  const lastCol = letters[headers.length-1];
  sheet.showGridLines = false;
  sheet.getRange(`A1:${lastCol}1`).values = [headers];
  if (rows.length) sheet.getRange(`A2:${lastCol}${rows.length+1}`).values = rows;
  sheet.getRange(`A1:${lastCol}1`).format = {fill:"#B53535",font:{bold:true,color:"#FFFFFF"},horizontalAlignment:"center",verticalAlignment:"center",wrapText:true,borders:{preset:"outside",style:"thin",color:"#D8C7C4"}};
  if (rows.length) sheet.getRange(`A2:${lastCol}${rows.length+1}`).format = {verticalAlignment:"top",wrapText:true,borders:{insideHorizontal:{style:"thin",color:"#E7DDDB"}}};
  sheet.getRange(`A1:${lastCol}1`).format.rowHeight = 30;
  widths.forEach((w,i)=>sheet.getRangeByIndexes(0,i,rows.length+1,1).format.columnWidth=w);
  sheet.freezePanes.freezeRows(1);
  return lastCol;
};

const sourceMaster = wb.worksheets.add("来源_标准化");
let lastCol = styleSheet(sourceMaster,["来源ID","标题","来源类型","发布机构","URL","可信等级","用途","访问日期","状态","支持字段","来源粒度"],sourceMasterRows,[10,30,18,20,58,11,28,13,14,32,15]);
sourceMaster.tables.add(`A1:${lastCol}${sourceMasterRows.length+1}`,true,"NormalizedSourceTable");

const entitySheets = [
  ["地点","Location",0,11,16],
  ["人物","Person",0,8,15],
  ["事件","Event",0,10,14],
  ["思政主题","IdeologyTheme",0,8,8],
  ["教学资源","TeachingResource",0,11,15]
];
const entitySourceRows = [];
const seenEntitySource = new Set();
const addEntitySource = (entityId,entityType,sourceId,role,status="现有来源") => {
  const key=`${entityId}|${sourceId}|${role}`;
  if (!entityId || !sourceId || seenEntitySource.has(key)) return;
  seenEntitySource.add(key);
  entitySourceRows.push([`ES${String(entitySourceRows.length+1).padStart(4,"0")}`,entityId,entityType,"SUPPORTED_BY",sourceId,role,status]);
};
for (const [sheetName, entityType, idCol, sourceCol, rowCount] of entitySheets) {
  const values = wb.worksheets.getItem(sheetName).getRangeByIndexes(0,0,rowCount+1,Math.max(idCol,sourceCol)+1).values;
  for (let i=1;i<values.length;i++) {
    for (const sourceId of String(values[i][sourceCol] || "").split(";").map(x=>x.trim()).filter(Boolean)) addEntitySource(values[i][idCol],entityType,sourceId,"原始来源");
  }
}
for (const [locationId, sourceIds] of Object.entries(officialLocationSources)) for (const sourceId of sourceIds) addEntitySource(locationId,"Location",sourceId,"官方详情页","新增核验");

const entitySourceSheet = wb.worksheets.add("实体来源关系");
lastCol = styleSheet(entitySourceSheet,["桥接ID","实体ID","实体类型","关系类型","来源ID","来源角色","核验说明"],entitySourceRows,[12,12,20,20,12,18,18]);
entitySourceSheet.tables.add(`A1:${lastCol}${entitySourceRows.length+1}`,true,"EntitySourceBridgeTable");

const relationValues = wb.worksheets.getItem("知识图谱关系").getRange("A1:I169").values;
const relationSourceRows = [];
const validSourceIds = new Set(sourceMasterRows.map(r=>r[0]));
const teachingValues = wb.worksheets.getItem("教学资源").getRange("A1:M16").values;
const teachingSources = new Map(teachingValues.slice(1).map(r=>[r[0],String(r[11]||"").split(";").map(x=>x.trim()).filter(Boolean)]));
const eventValues = wb.worksheets.getItem("事件").getRange("A1:L15").values;
const eventSources = new Map(eventValues.slice(1).map(r=>[r[0],String(r[10]||"").split(";").map(x=>x.trim()).filter(Boolean)]));
const invalidRelationSources = [];
for (let i=1;i<relationValues.length;i++) {
  const relationId=relationValues[i][0];
  const startId=relationValues[i][1];
  const endId=relationValues[i][4];
  const candidateSources = teachingSources.has(startId)
    ? teachingSources.get(startId)
    : (eventSources.has(startId) ? eventSources.get(startId)
      : (eventSources.has(endId) ? eventSources.get(endId)
        : String(relationValues[i][6] || "").split(";").map(x=>x.trim()).filter(Boolean)));
  for (const sourceId of candidateSources) {
    if (!validSourceIds.has(sourceId)) {
      invalidRelationSources.push([relationId,startId,sourceId]);
      continue;
    }
    relationSourceRows.push([`RS${String(relationSourceRows.length+1).padStart(4,"0")}`,relationId,"SUPPORTED_BY",sourceId,"由原多值字段拆分"]);
  }
}
const relationSourceSheet = wb.worksheets.add("关系来源关系");
lastCol = styleSheet(relationSourceSheet,["桥接ID","关系ID","关系类型","来源ID","说明"],relationSourceRows,[12,13,20,12,24]);
relationSourceSheet.tables.add(`A1:${lastCol}${relationSourceRows.length+1}`,true,"RelationSourceBridgeTable");

const controlRows = locationRows.slice(1).map(r => {
  const id=r[0];
  const graphStatus=id==="L016"?"EXCLUDED":(["L014","L015"].includes(id)?"STAGING":"PUBLISHED");
  const importFlag=id==="L016"?"NO":"YES";
  const reason=id==="L014"?"高德POI类型为餐饮，需核对历史建筑":id==="L015"?"当前仅有景区中心坐标":id==="L016"?"未定位独立纪念设施，建议改为历史主题":"已具备高置信度坐标或可用官方来源";
  return [id,r[1],r[2],graphStatus,importFlag,reason,officialLocationSources[id].join(";"),"2026-07-29"];
});
const controlSheet = wb.worksheets.add("导入控制");
lastCol = styleSheet(controlSheet,["实体ID","标准名称","当前类型","图谱状态","导入Location","状态理由","官方来源ID","审核日期"],controlRows,[11,28,28,15,15,38,20,13]);
controlSheet.tables.add(`A1:${lastCol}${controlRows.length+1}`,true,"GraphImportControlTable");
controlSheet.getRange("D2:D17").dataValidation = {rule:{type:"list",values:["PUBLISHED","STAGING","EXCLUDED"]}};
controlSheet.getRange("E2:E17").dataValidation = {rule:{type:"list",values:["YES","NO"]}};

const dictionaryRows = [
  ["PUBLISHED","已达到原型展示标准，默认允许地图和RAG查询"],
  ["STAGING","可以保留在数据库，但默认不进入正式问答或公开地图"],
  ["EXCLUDED","当前不作为Location导入；保留原始记录等待重新建模"],
  ["SUPPORTED_BY","实体或关系由某一来源提供证据支持"],
  ["GCJ-02","高德地图使用的坐标系，不应直接当作WGS84"],
  ["实体来源关系","一行只连接一个实体和一个来源，已拆分分号多值"],
  ["关系来源关系","一行只连接一条知识图谱关系和一个来源，已拆分分号多值"]
];
const dictSheet = wb.worksheets.add("导入说明");
lastCol = styleSheet(dictSheet,["术语","说明"],dictionaryRows,[22,70]);
dictSheet.tables.add(`A1:${lastCol}${dictionaryRows.length+1}`,true,"ImportDictionaryTable");

const checks = {
  published:controlRows.filter(r=>r[3]==="PUBLISHED").length,
  staging:controlRows.filter(r=>r[3]==="STAGING").length,
  excluded:controlRows.filter(r=>r[3]==="EXCLUDED").length,
  sources:sourceMasterRows.length,
  entitySourceRelations:entitySourceRows.length,
  relationSourceRelations:relationSourceRows.length
  ,invalidRelationSources:invalidRelationSources.length
};
const controlInspect = await wb.inspect({kind:"table",range:"导入控制!A1:H17",include:"values,formulas",tableMaxRows:20,tableMaxCols:10,maxChars:8000});
const sourceInspect = await wb.inspect({kind:"table",range:`来源_标准化!A1:K${sourceMasterRows.length+1}`,include:"values,formulas",tableMaxRows:8,tableMaxCols:11,maxChars:6000});
const errorScan = await wb.inspect({kind:"match",searchTerm:"#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",options:{useRegex:true,maxResults:300},summary:"final formula error scan"});
console.log(controlInspect.ndjson);
console.log(sourceInspect.ndjson);
console.log(errorScan.ndjson);

await fs.mkdir("D:/Projects/greatcreate_dachuang2/outputs/019fabd9-5322-7f30-8c5b-7e92f440be05",{recursive:true});
const output = await SpreadsheetFile.exportXlsx(wb);
await output.save(outputPath);
console.log(JSON.stringify({outputPath,checks}));
