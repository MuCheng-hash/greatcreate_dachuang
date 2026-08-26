<script setup lang="ts">
import {
  ArrowRight,
  BookOpen,
  Bot,
  Check,
  ChevronDown,
  Compass,
  Database,
  GraduationCap,
  Map,
  Network,
  Route,
  Search,
  Sparkles,
  Users,
} from "@lucide/vue";

const researchItems = [
  { number: "01", title: "资源精细化采集", text: "汇聚河北省、市、县、乡四级红色资源，经过清洗、校准与结构化，形成可复用的数据资产。", tags: ["多源采集", "数据 Schema"], icon: Database },
  { number: "02", title: "时空知识图谱", text: "把遗址、英雄、事件、纪念馆与行政区域建立时间和空间关联，让分散的故事可以互相印证。", tags: ["Neo4j", "实体关系"], icon: Network },
  { number: "03", title: "LLM 智能问答", text: "通过 RAG 检索真实知识，再用自然语言回答资源、人物和历史事件问题，兼顾准确与易懂。", tags: ["RAG", "可信引用"], icon: Bot },
  { number: "04", title: "地图与教育应用", text: "将知识图谱转化为可查、可看、可导航的地图体验，连接课堂学习、班级任务和实地实践。", tags: ["Vue 3", "地图可视化"], icon: Map },
];

const problemItems = [
  { title: "资源分散，查找成本高", from: "信息散落在官网、百科和纪念馆页面", to: "统一采集并建立资源之间的时空关联", icon: Search },
  { title: "教育内容离学生的生活太远", from: "乡村学生难以发现家乡身边的红色资源", to: "提供附近查询、班级任务和实践路线", icon: GraduationCap },
  { title: "传统展示缺少互动体验", from: "文字图片难以呈现人物、事件和地点的联系", to: "用地图、问答和路线规划串起学习过程", icon: Compass },
  { title: "知识与大模型之间缺少可靠连接", from: "通用模型可能回答得流畅，却无法保证事实依据", to: "知识图谱检索增强，回答只基于真实证据", icon: Check },
];

const featureItems = [
  { title: "红色资源地图", text: "查看遗址、纪念馆、人物和事件，发现红色文化就在身边。", icon: Map, tone: "red" },
  { title: "智能问答", text: "围绕资源、任务和历史故事自然提问，获得有来源的解释。", icon: Bot, tone: "blue" },
  { title: "附近资源查询", text: "按当前位置或乡镇查找可访问资源，支持后续路线探索。", icon: Compass, tone: "green" },
  { title: "教学方案生成", text: "教师输入目标并选择真实资源，生成可编辑的结构化方案。", icon: BookOpen, tone: "gold" },
  { title: "班级与学习任务", text: "教师布置任务，学生提交学习成果并查看教师反馈。", icon: Users, tone: "purple" },
  { title: "路线与实践探索", text: "从地图查询到实地参观，把红色教育从课本带到家乡。", icon: Route, tone: "teal" },
];

const roleItems = [
  { title: "学生", text: "探索身边资源，完成学习任务，向智能问答提问。", icon: GraduationCap },
  { title: "教师", text: "限定教学场景，组织班级学习，生成和复用教学方案。", icon: BookOpen },
  { title: "平台管理员", text: "维护资源、知识库和系统运行状态，保障平台可信可用。", icon: Network },
];

function scrollToSection(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}
</script>

<template>
  <div class="project-home">
    <header class="project-header">
      <RouterLink class="project-brand" to="/" aria-label="红启乡智项目首页">
        <span class="project-mark">乡</span>
        <span class="project-brand-copy"><strong>红启乡智</strong><small>红色文化乡村教育赋能平台</small></span>
      </RouterLink>
      <nav class="project-nav" aria-label="项目页面导航">
        <button type="button" @click="scrollToSection('overview')">项目概览</button>
        <button type="button" @click="scrollToSection('research')">研究内容</button>
        <button type="button" @click="scrollToSection('problems')">解决问题</button>
        <button type="button" @click="scrollToSection('features')">核心功能</button>
      </nav>
      <RouterLink class="project-login" to="/login">登录 <ArrowRight :size="16" /></RouterLink>
    </header>

    <main>
      <section id="overview" class="project-hero">
        <div class="hero-copy">
          <p class="eyebrow"><Sparkles :size="15" /> 河北红色文化 × 乡村教育</p>
          <h1>让红色文化，<em>从家乡出发</em></h1>
          <p class="hero-description">以河北乡镇红色文化资源为基础，融合时空知识图谱、智能问答和地图可视化，为乡村学校提供可查、可学、可实践的本土化教育工具。</p>
          <div class="hero-actions">
            <RouterLink class="hero-primary" to="/login">进入平台 <ArrowRight :size="17" /></RouterLink>
            <button class="hero-secondary" type="button" @click="scrollToSection('research')">了解研究内容 <ChevronDown :size="17" /></button>
          </div>
          <div class="hero-meta"><span>时空知识图谱</span><i></i><span>RAG 智能问答</span><i></i><span>地图实践学习</span></div>
        </div>
        <div class="hero-visual" aria-label="学校与周边红色资源示意图">
          <div class="hero-image"></div>
          <div class="map-grid"></div>
          <div class="map-line map-line-one"></div><div class="map-line map-line-two"></div>
          <div class="resource-pin pin-one"><Map :size="15" /><span>红色遗址</span></div>
          <div class="resource-pin pin-two"><BookOpen :size="15" /><span>纪念馆</span></div>
          <div class="resource-pin pin-three"><Users :size="15" /><span>学校</span></div>
          <div class="visual-caption"><span class="caption-dot"></span><span>把家乡资源带进学习现场</span></div>
        </div>
      </section>

      <section class="intro-strip"><div class="section-label">项目定位</div><p>以真实数据为底座，以知识图谱连接信息，以大模型降低理解门槛，形成“资源—学习—实践”的红色文化教育闭环。</p><span class="strip-index">01 / 06</span></section>

      <section id="research" class="content-section research-section">
        <div class="section-heading"><div><p class="eyebrow">从资料到应用</p><h2>四项研究，构成一条完整技术路径</h2></div><p>平台不只是展示红色故事，而是把分散的地方资料整理成能够被检索、关联和用于教学的知识资产。</p></div>
        <div class="research-grid"><article v-for="item in researchItems" :key="item.number" class="research-item"><div class="item-top"><span class="item-number">{{ item.number }}</span><component :is="item.icon" :size="21" /></div><h3>{{ item.title }}</h3><p>{{ item.text }}</p><div class="item-tags"><span v-for="tag in item.tags" :key="tag">{{ tag }}</span></div></article></div>
        <div class="tech-path"><span>资源采集</span><ArrowRight :size="16" /><span>数据结构化</span><ArrowRight :size="16" /><span>时空知识图谱</span><ArrowRight :size="16" /><span>RAG 智能问答</span><ArrowRight :size="16" /><span>地图与教育应用</span></div>
      </section>

      <section id="problems" class="content-section problems-section"><div class="section-heading"><div><p class="eyebrow">为什么需要它</p><h2>把真实的教育难题，转化为可使用的工具</h2></div><p>从资源查找，到课堂理解，再到走出教室实践，每一步都对应乡村学校的实际使用场景。</p></div><div class="problem-list"><article v-for="item in problemItems" :key="item.title" class="problem-item"><div class="problem-icon"><component :is="item.icon" :size="20" /></div><div><h3>{{ item.title }}</h3><p class="problem-from">{{ item.from }}</p><div class="problem-solution"><span>平台应对</span><strong>{{ item.to }}</strong></div></div></article></div></section>

      <section id="features" class="content-section feature-section"><div class="section-heading"><div><p class="eyebrow">平台功能</p><h2>让每一类用户，都有清晰的下一步</h2></div><p>学生、教师和管理员共享同一份可信资源底座，在各自的工作台完成学习、教学与管理。</p></div><div class="feature-grid"><article v-for="feature in featureItems" :key="feature.title" class="feature-item" :class="`tone-${feature.tone}`"><div class="feature-icon"><component :is="feature.icon" :size="20" /></div><h3>{{ feature.title }}</h3><p>{{ feature.text }}</p></article></div></section>

      <section class="roles-section"><div class="role-intro"><p class="eyebrow">服务对象</p><h2>一套平台，连接三种学习与工作视角</h2><p>登录后，系统会根据账号角色自动进入对应工作台。</p></div><div class="role-list"><article v-for="role in roleItems" :key="role.title" class="role-item"><component :is="role.icon" :size="21" /><div><h3>{{ role.title }}</h3><p>{{ role.text }}</p></div><ArrowRight :size="17" /></article></div></section>

      <section class="outcome-section"><div><p class="eyebrow">预期成果</p><h2>让红色文化资源，成为可持续的教育资产</h2></div><div class="outcome-grid"><span>标准化红色文化数据资产</span><span>可复用的时空知识图谱框架</span><span>地图、问答、路线一体化应用</span><span>乡村学校本土化学习工具</span></div></section>
    </main>
    <footer class="project-footer"><div><span class="project-mark small-mark">乡</span><strong>红启乡智</strong><span>知识图谱融合大模型的红色文化乡村教育赋能平台</span></div><RouterLink class="footer-login" to="/login">登录工作台 <ArrowRight :size="16" /></RouterLink></footer>
  </div>
</template>

<style scoped>
.project-home {
  --ink: #1d2d25;
  --green-deep: #17352a;
  --green: #2c5e46;
  --red: #9f3a2e;
  --red-dark: #7d2b21;
  --red-soft: #f8e9e5;
  --paper: #f6f1e6;
  --line: #e0d9c8;
  --gold: #a97d2f;
  min-height: 100vh; overflow: hidden; background: var(--paper); color: var(--ink);
}
.project-home h1, .project-home h2, .project-home h3 { font-family: var(--font-display); letter-spacing: .015em; }
.project-header {
  position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 30px;
  min-height: 74px; padding: 0 clamp(18px, 5vw, 76px);
  border-bottom: 1px solid rgba(215,207,188,.9);
  background: rgba(246,241,230,.88); backdrop-filter: blur(16px);
}
.project-brand { display: flex; align-items: center; gap: 11px; min-width: 235px; }
.project-mark {
  display: grid; place-items: center; position: relative; width: 40px; height: 40px; border-radius: 10px;
  background: linear-gradient(145deg, #b04436, #8a2f24); color: #fff; font-weight: 800; font-size: 20px;
  box-shadow: 0 5px 16px rgba(159,58,46,.35), inset 0 1px 0 rgba(255,255,255,.22);
}
.project-mark::after { content: ""; position: absolute; inset: 4px; border-radius: 6px; border: 1px solid rgba(255,255,255,.36); }
.project-brand-copy { display: grid; gap: 2px; }
.project-brand-copy strong { font-size: 16px; letter-spacing: .08em; font-family: var(--font-display); }
.project-brand-copy small { color: #74816f; font-size: 11px; letter-spacing: .03em; }
.project-nav { display: flex; align-items: center; justify-content: center; gap: clamp(14px, 2.5vw, 32px); flex: 1; }
.project-nav button {
  position: relative; padding: 8px 0; border: 0; background: none; color: #617067; font-size: 13px; font-weight: 600; cursor: pointer;
  transition: color 150ms ease;
}
.project-nav button::after {
  content: ""; position: absolute; left: 50%; bottom: 0; width: 0; height: 2px;
  background: var(--red); transform: translateX(-50%); transition: width 180ms ease;
}
.project-nav button:hover { color: var(--red); }
.project-nav button:hover::after { width: 100%; }
.project-login, .hero-primary, .footer-login {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px; border-radius: 8px;
  font-size: 13px; font-weight: 700; transition: transform 150ms ease, box-shadow 150ms ease, background 150ms ease, border-color 150ms ease;
}
.project-login { padding: 10px 16px; border: 1px solid #cdc5b2; color: var(--green-deep); background: rgba(255,255,255,.4); }
.project-login:hover { border-color: var(--red); color: var(--red); transform: translateY(-1px); }

.project-hero {
  position: relative; display: grid; grid-template-columns: minmax(0, .94fr) minmax(420px, 1.06fr);
  gap: clamp(30px, 6vw, 90px); align-items: center; width: min(100% - 40px, 1300px);
  min-height: 640px; margin: 0 auto; padding: 72px 0 74px;
}
.project-hero::before {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .5;
  background-image: radial-gradient(rgba(159,58,46,.09) 1px, transparent 1.4px);
  background-size: 34px 34px;
  -webkit-mask-image: radial-gradient(900px 600px at 20% 40%, #000, transparent 75%);
  mask-image: radial-gradient(900px 600px at 20% 40%, #000, transparent 75%);
}
.hero-copy { position: relative; }
.hero-copy .eyebrow, .hero-copy h1, .hero-copy .hero-description, .hero-copy .hero-actions, .hero-copy .hero-meta {
  animation: home-rise .7s cubic-bezier(.22,.61,.36,1) both;
}
.hero-copy h1 { animation-delay: .08s; }
.hero-copy .hero-description { animation-delay: .16s; }
.hero-copy .hero-actions { animation-delay: .24s; }
.hero-copy .hero-meta { animation-delay: .32s; }
@keyframes home-rise { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
.hero-copy .eyebrow {
  display: inline-flex; align-items: center; gap: 8px; margin-bottom: 18px; padding: 7px 13px;
  border: 1px solid #e3b9b2; border-radius: 999px; background: var(--red-soft);
  color: var(--red); font-size: 12px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase;
}
.hero-copy h1 {
  max-width: 660px; margin: 0 0 24px; color: var(--green-deep);
  font-size: clamp(44px, 6.2vw, 80px); line-height: 1.05; letter-spacing: .01em; font-weight: 700;
}
.hero-copy h1 em { display: block; position: relative; color: var(--red); font-style: normal; }
.hero-copy h1 em::after {
  content: ""; position: absolute; left: 2px; right: 6px; bottom: 2px; height: 12px;
  background: linear-gradient(90deg, rgba(159,58,46,.18), rgba(169,125,47,.12));
  border-radius: 3px; z-index: -1;
}
.hero-description { max-width: 590px; margin: 0; color: #5c6b60; font-size: 17px; line-height: 1.95; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 32px; }
.hero-primary {
  min-height: 46px; padding: 0 20px; background: linear-gradient(180deg, #a94336, #933226); color: #fff;
  box-shadow: 0 8px 20px rgba(159,58,46,.28);
}
.hero-primary:hover { transform: translateY(-2px); box-shadow: 0 12px 26px rgba(159,58,46,.34); }
.hero-secondary {
  display: inline-flex; align-items: center; gap: 8px; min-height: 46px; padding: 0 17px;
  border: 1px solid #cdc5b2; border-radius: 8px; background: rgba(255,255,255,.35); color: var(--green-deep); font-weight: 700; cursor: pointer;
  transition: background 150ms ease, transform 150ms ease;
}
.hero-secondary:hover { background: #fff; transform: translateY(-2px); }
.hero-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 36px; color: #849188; font-size: 11px; letter-spacing: .06em; }
.hero-meta i { width: 3px; height: 3px; border-radius: 50%; background: var(--gold); }

.hero-visual {
  position: relative; min-height: 490px; overflow: hidden; border-radius: 12px;
  background: #c4d1c3; box-shadow: 24px 30px 80px rgba(41,66,50,.18);
  animation: home-rise .8s cubic-bezier(.22,.61,.36,1) .1s both;
}
.hero-visual::after {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .35;
  background-image: radial-gradient(rgba(255,255,255,.3) 1px, transparent 1.2px);
  background-size: 22px 22px;
}
.hero-image {
  position: absolute; inset: 0;
  background:
    linear-gradient(180deg, rgba(18,48,34,.35), rgba(18,48,34,.72)),
    url('/login-campus.webp') center/cover;
  filter: saturate(.86);
}
.map-grid {
  position: absolute; inset: 0; opacity: .22;
  background-image: linear-gradient(rgba(255,255,255,.5) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.5) 1px, transparent 1px);
  background-size: 44px 44px; transform: rotate(-9deg) scale(1.22);
}
.map-line { position: absolute; height: 1px; background: rgba(244,250,241,.85); transform-origin: left center; }
.map-line::after { content: ""; position: absolute; right: -5px; top: -3px; width: 7px; height: 7px; border-radius: 50%; background: var(--gold); box-shadow: 0 0 12px rgba(169,125,47,.9); }
.map-line-one { width: 68%; top: 45%; left: 15%; transform: rotate(-23deg); }
.map-line-two { width: 53%; top: 53%; left: 19%; transform: rotate(27deg); }
.resource-pin {
  position: absolute; display: inline-flex; align-items: center; gap: 7px; padding: 8px 12px;
  border: 1px solid rgba(255,255,255,.42); border-radius: 8px;
  background: rgba(27,55,40,.8); color: #fff; font-size: 11px; letter-spacing: .04em;
  backdrop-filter: blur(6px); box-shadow: 0 8px 20px rgba(10,26,18,.35);
  animation: pin-float 4.6s ease-in-out infinite;
}
.resource-pin svg { color: #eac98e; }
.pin-one { top: 22%; left: 16%; animation-delay: .3s; }
.pin-two { top: 56%; right: 11%; animation-delay: 1.2s; }
.pin-three { bottom: 15%; left: 35%; background: rgba(159,58,46,.92); animation-delay: 2s; }
.pin-three svg { color: #f3c8c1; }
@keyframes pin-float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-9px); } }
.visual-caption {
  position: absolute; right: 18px; bottom: 18px; z-index: 2; display: flex; align-items: center; gap: 9px;
  padding: 10px 13px; border-radius: 8px; background: rgba(255,253,248,.92); color: var(--green-deep); font-size: 11px; font-weight: 600;
  box-shadow: 0 8px 24px rgba(15,32,23,.22);
}
.caption-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--red); box-shadow: 0 0 0 4px rgba(159,58,46,.15); }

.intro-strip {
  position: relative; display: grid; grid-template-columns: 120px 1fr auto; gap: 28px; align-items: center;
  width: min(100% - 40px, 1300px); margin: 0 auto; padding: 24px 0;
  border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
}
.section-label { color: var(--gold); font-size: 11px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; }
.intro-strip p { max-width: 780px; margin: 0; color: #435449; font-size: 15px; line-height: 1.85; }
.strip-index { color: #9aa79d; font-size: 12px; letter-spacing: .12em; font-family: var(--font-display); }

.content-section { width: min(100% - 40px, 1300px); margin: 0 auto; padding: 116px 0; scroll-margin-top: 74px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 30px; margin-bottom: 46px; }
.section-heading h2, .roles-section h2, .outcome-section h2 {
  max-width: 690px; margin: 0; color: var(--green-deep);
  font-size: clamp(30px, 4.2vw, 48px); line-height: 1.18; font-weight: 700;
}
.section-heading > p { max-width: 400px; margin: 0; color: #6b786f; font-size: 14px; line-height: 1.85; }

.research-grid {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
}
.research-item {
  position: relative; min-height: 300px; padding: 24px 25px 27px; border-right: 1px solid var(--line);
  transition: background 200ms ease;
  animation: home-rise .65s cubic-bezier(.22,.61,.36,1) both;
}
.research-item:nth-child(2) { animation-delay: .08s; }
.research-item:nth-child(3) { animation-delay: .16s; }
.research-item:nth-child(4) { animation-delay: .24s; }
.research-item:last-child { border-right: 0; }
.research-item::before {
  content: ""; position: absolute; top: -1px; left: 0; width: 0; height: 3px; background: var(--red);
  transition: width 260ms ease;
}
.research-item:hover { background: rgba(255,255,255,.55); }
.research-item:hover::before { width: 100%; }
.item-top { display: flex; align-items: center; justify-content: space-between; color: var(--green); }
.item-number { color: var(--red); font-size: 12px; font-weight: 800; letter-spacing: .12em; font-family: var(--font-display); }
.research-item h3 { margin: 50px 0 12px; font-size: 19px; }
.research-item p { min-height: 92px; margin: 0; color: #65736a; font-size: 13px; line-height: 1.85; }
.item-tags { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 18px; }
.item-tags span { padding: 5px 9px; border: 1px solid #d8d0bc; border-radius: 999px; color: #6c7668; font-size: 10px; letter-spacing: .04em; }
.tech-path { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 10px; margin-top: 34px; color: var(--green-deep); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.tech-path span { padding: 8px 15px; border: 1px solid var(--line); border-radius: 999px; background: rgba(255,255,255,.5); }
.tech-path svg { color: var(--red); }

.problems-section { border-top: 1px solid var(--line); }
.problem-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 50px; border-top: 1px solid var(--line); }
.problem-item {
  display: grid; grid-template-columns: 46px 1fr; gap: 18px; min-height: 180px; padding: 26px 0;
  border-bottom: 1px solid var(--line);
  animation: home-rise .65s cubic-bezier(.22,.61,.36,1) both;
}
.problem-item:nth-child(2) { animation-delay: .1s; }
.problem-item:nth-child(3) { animation-delay: .2s; }
.problem-item:nth-child(4) { animation-delay: .3s; }
.problem-icon {
  display: grid; place-items: center; width: 42px; height: 42px; border: 1px solid #dfb9b3;
  border-radius: 12px; color: var(--red); background: var(--red-soft);
}
.problem-item h3 { margin: 3px 0 8px; font-size: 18px; }
.problem-from { margin: 0 0 16px; color: #7a857d; font-size: 12px; }
.problem-solution { display: flex; align-items: baseline; gap: 10px; color: var(--green); font-size: 12px; }
.problem-solution span { color: var(--gold); font-size: 10px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
.problem-solution strong { font-weight: 700; }

.feature-section { border-top: 1px solid var(--line); }
.feature-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.feature-item {
  min-height: 186px; padding: 26px; border: 1px solid var(--line); border-radius: 12px;
  background: var(--surface, #fffdf8); box-shadow: var(--shadow-sm, 0 3px 12px rgba(31,45,36,.06));
  animation: home-rise .65s cubic-bezier(.22,.61,.36,1) both;
}
.feature-item:nth-child(2) { animation-delay: .08s; }
.feature-item:nth-child(3) { animation-delay: .16s; }
.feature-item:nth-child(4) { animation-delay: .24s; }
.feature-item:nth-child(5) { animation-delay: .32s; }
.feature-item:nth-child(6) { animation-delay: .4s; }
.feature-item:hover { transform: translateY(-4px); box-shadow: 0 16px 34px rgba(31,48,38,.12); transition: transform .22s ease, box-shadow .22s ease; }
.feature-icon { display: grid; place-items: center; width: 40px; height: 40px; margin-bottom: 26px; border-radius: 10px; }
.feature-item h3 { margin: 0 0 9px; font-size: 18px; }
.feature-item p { margin: 0; color: #6d796f; font-size: 13px; line-height: 1.85; }
.tone-red .feature-icon { color: var(--red); background: var(--red-soft); }
.tone-blue .feature-icon { color: #28669e; background: #e9f1f7; }
.tone-green .feature-icon { color: var(--green); background: #e7f1e9; }
.tone-gold .feature-icon { color: #966a2c; background: #f6efdf; }
.tone-purple .feature-icon { color: #6d5b85; background: #eeeaf3; }
.tone-teal .feature-icon { color: #247a78; background: #e4f2f0; }

.roles-section {
  display: grid; grid-template-columns: minmax(270px, .8fr) minmax(0, 1.2fr); gap: 70px;
  width: min(100% - 40px, 1300px); margin: 0 auto; padding: 84px 0; border-top: 1px solid var(--line);
}
.role-intro > p:last-child { max-width: 330px; margin-top: 18px; color: #6d796f; font-size: 14px; line-height: 1.85; }
.role-list { border-top: 1px solid var(--line); }
.role-item {
  display: grid; grid-template-columns: 34px 1fr 22px; gap: 16px; align-items: center;
  min-height: 96px; border-bottom: 1px solid var(--line); color: var(--green);
  transition: background 160ms ease, padding 160ms ease;
}
.role-item:hover { background: rgba(255,255,255,.55); padding-left: 8px; }
.role-item h3 { margin: 0 0 5px; color: var(--ink); font-size: 17px; }
.role-item p { margin: 0; color: #718077; font-size: 12px; line-height: 1.65; }
.role-item > svg:last-child { color: #a3afa5; transition: color 160ms ease, transform 160ms ease; }
.role-item:hover > svg:last-child { color: var(--red); transform: translateX(3px); }

.outcome-section {
  position: relative; display: grid; grid-template-columns: .9fr 1.1fr; gap: 80px; align-items: center;
  padding: 66px max(20px, calc((100vw - 1300px)/2));
  background:
    radial-gradient(700px 300px at 85% 0%, rgba(169,125,47,.2), transparent 60%),
    linear-gradient(160deg, #1d3d2f, #142b21);
  color: #fff; overflow: hidden;
}
.outcome-section::after {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .3;
  background-image: radial-gradient(rgba(255,255,255,.14) 1px, transparent 1.2px);
  background-size: 30px 30px;
}
.outcome-section > div { position: relative; z-index: 1; }
.outcome-section h2 { color: #fff; }
.outcome-section .eyebrow { color: #e0b877; }
.outcome-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; border-top: 1px solid rgba(255,255,255,.24); }
.outcome-grid span { padding: 19px 0; border-bottom: 1px solid rgba(255,255,255,.24); color: #d8e3da; font-size: 13px; letter-spacing: .04em; }
.outcome-grid span:nth-child(odd) { margin-right: 26px; }
.project-footer {
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
  padding: 28px max(20px, calc((100vw - 1300px)/2)); background: #10291e; color: #c6d4c9; font-size: 12px;
}
.project-footer > div { display: flex; align-items: center; flex-wrap: wrap; gap: 11px; }
.project-footer strong { color: #fff; font-family: var(--font-display); letter-spacing: .08em; }
.small-mark { width: 28px; height: 28px; font-size: 14px; border-radius: 8px; }
.footer-login { color: #fff; }
.footer-login:hover { color: #f1b9b4; transform: translateX(3px); }

@media (max-width: 900px) {
  .project-header { gap: 16px; padding: 0 20px; }
  .project-nav { order: 3; flex: 0 0 100%; justify-content: flex-start; overflow-x: auto; padding-bottom: 10px; }
  .project-header { flex-wrap: wrap; padding-top: 12px; }
  .project-brand { flex: 1; }
  .project-login { flex: 0 0 auto; }
  .project-hero { grid-template-columns: 1fr; min-height: auto; padding-top: 48px; }
  .hero-visual { min-height: 400px; }
  .research-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .research-item:nth-child(2) { border-right: 0; }
  .research-item:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .roles-section, .outcome-section { grid-template-columns: 1fr; gap: 35px; }
  .outcome-section { padding-top: 60px; padding-bottom: 60px; }
}
@media (max-width: 640px) {
  .project-header { min-height: 67px; padding: 10px 14px 0; }
  .project-brand { min-width: 0; }
  .project-brand-copy small { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .project-nav { gap: 22px; margin: 0 -2px; }
  .project-nav button { white-space: nowrap; }
  .project-login { padding: 9px 11px; }
  .project-login svg { display: none; }
  .project-hero, .content-section, .intro-strip, .roles-section { width: min(100% - 28px, 1300px); }
  .project-hero { padding: 40px 0 50px; }
  .hero-copy h1 { font-size: clamp(40px, 13vw, 60px); }
  .hero-description { font-size: 15px; }
  .hero-visual { min-height: 310px; }
  .hero-meta { gap: 7px; line-height: 1.8; }
  .intro-strip { grid-template-columns: 1fr auto; gap: 10px; padding: 18px 0; }
  .intro-strip p { grid-column: 1 / -1; font-size: 13px; }
  .content-section { padding: 76px 0; }
  .section-heading { display: block; margin-bottom: 30px; }
  .section-heading > p { margin-top: 16px; }
  .research-grid, .problem-list, .feature-grid { grid-template-columns: 1fr; }
  .research-item { min-height: 0; border-right: 0; border-bottom: 1px solid var(--line); }
  .research-item:last-child { border-bottom: 0; }
  .research-item h3 { margin-top: 30px; }
  .research-item p { min-height: 0; }
  .tech-path { justify-content: flex-start; line-height: 2.2; }
  .problem-item { min-height: 0; }
  .feature-item { min-height: 0; }
  .outcome-section { padding: 54px 20px; }
  .outcome-grid { grid-template-columns: 1fr; }
  .outcome-grid span:nth-child(odd) { margin-right: 0; }
  .project-footer { align-items: flex-start; flex-direction: column; padding: 24px 14px 88px; }
  .project-footer > div { line-height: 1.7; }
}
@media (prefers-reduced-motion: reduce) {
  .project-home *, .project-home *::before, .project-home *::after {
    scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important;
  }
}
</style>
