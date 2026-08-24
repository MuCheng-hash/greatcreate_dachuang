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
.project-home { --ink: #1d2d25; --green-deep: #193d2d; --green: #2f6b4f; --red: #a6382f; --red-dark: #7f2824; --paper: #f7f8f3; --line: #d7e0d8; min-height: 100vh; overflow: hidden; background: var(--paper); color: var(--ink); }
.project-header { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 30px; min-height: 74px; padding: 0 clamp(18px, 5vw, 76px); border-bottom: 1px solid rgba(215,224,216,.88); background: rgba(247,248,243,.92); backdrop-filter: blur(16px); }
.project-brand { display: flex; align-items: center; gap: 10px; min-width: 235px; }.project-mark { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 6px; background: var(--red); color: white; font-weight: 800; font-size: 20px; }.project-brand-copy { display: grid; gap: 2px; }.project-brand-copy strong { font-size: 16px; letter-spacing: .04em; }.project-brand-copy small { color: #708075; font-size: 11px; }.project-nav { display: flex; align-items: center; justify-content: center; gap: clamp(14px, 2.5vw, 32px); flex: 1; }.project-nav button { padding: 7px 0; border: 0; background: none; color: #617067; font-size: 13px; cursor: pointer; }.project-nav button:hover { color: var(--red); }.project-login, .hero-primary, .footer-login { display: inline-flex; align-items: center; justify-content: center; gap: 8px; border-radius: 5px; font-size: 13px; font-weight: 700; }.project-login { padding: 10px 15px; border: 1px solid #c8d4cb; color: var(--green-deep); }.project-login:hover { border-color: var(--red); color: var(--red); }
.project-hero { display: grid; grid-template-columns: minmax(0, .94fr) minmax(420px, 1.06fr); gap: clamp(30px, 6vw, 90px); align-items: center; width: min(100% - 40px, 1300px); min-height: 620px; margin: 0 auto; padding: 68px 0 70px; }.eyebrow { display: inline-flex; align-items: center; gap: 7px; margin-bottom: 15px; color: var(--red); font-size: 12px; font-weight: 800; letter-spacing: .09em; text-transform: uppercase; }.hero-copy h1 { max-width: 650px; margin: 0 0 22px; color: var(--green-deep); font-size: clamp(42px, 6vw, 78px); line-height: 1.04; letter-spacing: -.045em; }.hero-copy h1 em { display: block; color: var(--red); font-style: normal; }.hero-description { max-width: 580px; margin: 0; color: #59695f; font-size: 17px; line-height: 1.9; }.hero-actions { display: flex; flex-wrap: wrap; gap: 11px; margin-top: 30px; }.hero-primary { min-height: 45px; padding: 0 18px; background: var(--red); color: white; }.hero-primary:hover { background: var(--red-dark); }.hero-secondary { display: inline-flex; align-items: center; gap: 7px; min-height: 45px; padding: 0 15px; border: 1px solid #c8d4cb; border-radius: 5px; background: transparent; color: var(--green-deep); font-weight: 700; cursor: pointer; }.hero-secondary:hover { background: white; }.hero-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 34px; color: #849188; font-size: 11px; }.hero-meta i { width: 3px; height: 3px; border-radius: 50%; background: var(--red); }.hero-visual { position: relative; min-height: 470px; overflow: hidden; border-radius: 7px; background: #cad5c9; box-shadow: 20px 25px 70px rgba(41,66,50,.15); }.hero-image { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(18,48,34,.72), rgba(18,48,34,.05)), url('/login-campus.webp') center/cover; filter: saturate(.82); }.map-grid { position: absolute; inset: 0; opacity: .23; background-image: linear-gradient(rgba(255,255,255,.45) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.45) 1px, transparent 1px); background-size: 42px 42px; transform: rotate(-9deg) scale(1.2); }.map-line { position: absolute; height: 1px; background: rgba(241,248,238,.8); transform-origin: left center; }.map-line-one { width: 68%; top: 45%; left: 15%; transform: rotate(-23deg); }.map-line-two { width: 53%; top: 53%; left: 19%; transform: rotate(27deg); }.resource-pin { position: absolute; display: inline-flex; align-items: center; gap: 6px; padding: 7px 9px; border: 1px solid rgba(255,255,255,.42); border-radius: 4px; background: rgba(27,55,40,.78); color: white; font-size: 11px; backdrop-filter: blur(5px); }.pin-one { top: 22%; left: 18%; }.pin-two { top: 57%; right: 12%; }.pin-three { bottom: 16%; left: 36%; background: rgba(166,56,47,.9); }.visual-caption { position: absolute; right: 18px; bottom: 18px; display: flex; align-items: center; gap: 8px; padding: 9px 11px; background: rgba(255,255,255,.9); color: var(--green-deep); font-size: 11px; }.caption-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--red); }
.intro-strip { display: grid; grid-template-columns: 110px 1fr auto; gap: 28px; align-items: center; width: min(100% - 40px, 1300px); margin: 0 auto; padding: 22px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }.section-label { color: var(--red); font-size: 11px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }.intro-strip p { max-width: 770px; margin: 0; color: #435449; font-size: 15px; line-height: 1.8; }.strip-index { color: #9aa79d; font-size: 11px; }
.content-section { width: min(100% - 40px, 1300px); margin: 0 auto; padding: 112px 0; scroll-margin-top: 74px; }.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 30px; margin-bottom: 42px; }.section-heading h2, .roles-section h2, .outcome-section h2 { max-width: 670px; margin: 0; color: var(--green-deep); font-size: clamp(28px, 4vw, 45px); line-height: 1.18; letter-spacing: -.035em; }.section-heading > p { max-width: 380px; margin: 0; color: #6b786f; font-size: 14px; line-height: 1.8; }.research-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }.research-item { min-height: 285px; padding: 22px 23px 25px; border-right: 1px solid var(--line); }.research-item:last-child { border-right: 0; }.item-top { display: flex; align-items: center; justify-content: space-between; color: var(--green); }.item-number { color: var(--red); font-size: 12px; font-weight: 800; }.research-item h3 { margin: 47px 0 12px; font-size: 18px; }.research-item p { min-height: 90px; margin: 0; color: #65736a; font-size: 13px; line-height: 1.8; }.item-tags { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 17px; }.item-tags span { padding: 5px 7px; border: 1px solid #d5dfd6; color: #65736a; font-size: 10px; }.tech-path { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 10px; margin-top: 31px; color: var(--green-deep); font-size: 12px; font-weight: 700; }.tech-path svg { color: var(--red); }.problems-section { border-top: 1px solid var(--line); }.problem-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 50px; border-top: 1px solid var(--line); }.problem-item { display: grid; grid-template-columns: 44px 1fr; gap: 17px; min-height: 174px; padding: 24px 0; border-bottom: 1px solid var(--line); }.problem-icon { display: grid; place-items: center; width: 40px; height: 40px; border: 1px solid #d7b4af; border-radius: 50%; color: var(--red); }.problem-item h3 { margin: 3px 0 8px; font-size: 17px; }.problem-from { margin: 0 0 15px; color: #7a857d; font-size: 12px; }.problem-solution { display: flex; align-items: baseline; gap: 9px; color: var(--green); font-size: 12px; }.problem-solution span { color: var(--red); font-size: 10px; font-weight: 800; letter-spacing: .06em; text-transform: uppercase; }.problem-solution strong { font-weight: 700; }.feature-section { border-top: 1px solid var(--line); }.feature-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }.feature-item { min-height: 180px; padding: 24px; border: 1px solid var(--line); background: #fff; }.feature-item:hover { transform: translateY(-3px); box-shadow: 0 12px 28px rgba(31,48,38,.08); transition: transform .2s ease, box-shadow .2s ease; }.feature-icon { display: grid; place-items: center; width: 38px; height: 38px; margin-bottom: 25px; border-radius: 5px; }.feature-item h3 { margin: 0 0 8px; font-size: 17px; }.feature-item p { margin: 0; color: #6d796f; font-size: 13px; line-height: 1.8; }.tone-red .feature-icon { color: var(--red); background: #f8e9e7; }.tone-blue .feature-icon { color: #28669e; background: #e9f1f7; }.tone-green .feature-icon { color: var(--green); background: #e7f1e9; }.tone-gold .feature-icon { color: #966a2c; background: #f6efdf; }.tone-purple .feature-icon { color: #6d5b85; background: #eeeaf3; }.tone-teal .feature-icon { color: #247a78; background: #e4f2f0; }
.roles-section { display: grid; grid-template-columns: minmax(270px, .8fr) minmax(0, 1.2fr); gap: 70px; width: min(100% - 40px, 1300px); margin: 0 auto; padding: 78px 0; border-top: 1px solid var(--line); }.role-intro > p:last-child { max-width: 330px; margin-top: 18px; color: #6d796f; font-size: 14px; line-height: 1.8; }.role-list { border-top: 1px solid var(--line); }.role-item { display: grid; grid-template-columns: 30px 1fr 20px; gap: 15px; align-items: center; min-height: 92px; border-bottom: 1px solid var(--line); color: var(--green); }.role-item h3 { margin: 0 0 5px; color: var(--ink); font-size: 16px; }.role-item p { margin: 0; color: #718077; font-size: 12px; line-height: 1.6; }.role-item > svg:last-child { color: #a3afa5; }.outcome-section { display: grid; grid-template-columns: .9fr 1.1fr; gap: 80px; align-items: center; padding: 62px max(20px, calc((100vw - 1300px)/2)); background: var(--green-deep); color: white; }.outcome-section h2 { color: white; }.outcome-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; border-top: 1px solid rgba(255,255,255,.22); }.outcome-grid span { padding: 18px 0; border-bottom: 1px solid rgba(255,255,255,.22); color: #d6e2d8; font-size: 13px; }.outcome-grid span:nth-child(odd) { margin-right: 24px; }.project-footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 26px max(20px, calc((100vw - 1300px)/2)); background: #10291e; color: #c6d4c9; font-size: 12px; }.project-footer > div { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }.project-footer strong { color: white; }.small-mark { width: 26px; height: 26px; font-size: 14px; }.footer-login { color: white; }.footer-login:hover { color: #f1b9b4; }
@media (max-width: 900px) { .project-header { gap: 16px; padding: 0 20px; }.project-nav { order: 3; flex: 0 0 100%; justify-content: flex-start; overflow-x: auto; padding-bottom: 10px; }.project-header { flex-wrap: wrap; padding-top: 12px; }.project-brand { flex: 1; }.project-login { flex: 0 0 auto; }.project-hero { grid-template-columns: 1fr; min-height: auto; padding-top: 45px; }.hero-visual { min-height: 380px; }.research-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.research-item:nth-child(2) { border-right: 0; }.research-item:nth-child(-n+2) { border-bottom: 1px solid var(--line); }.roles-section, .outcome-section { grid-template-columns: 1fr; gap: 35px; }.outcome-section { padding-top: 55px; padding-bottom: 55px; } }
@media (max-width: 640px) { .project-header { min-height: 67px; padding: 10px 14px 0; }.project-brand { min-width: 0; }.project-brand-copy small { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.project-nav { gap: 22px; margin: 0 -2px; }.project-nav button { white-space: nowrap; }.project-login { padding: 9px 11px; }.project-login svg { display: none; }.project-hero, .content-section, .intro-strip, .roles-section { width: min(100% - 28px, 1300px); }.project-hero { padding: 37px 0 47px; }.hero-copy h1 { font-size: clamp(40px, 13vw, 58px); }.hero-description { font-size: 15px; }.hero-visual { min-height: 300px; }.hero-meta { gap: 7px; line-height: 1.8; }.intro-strip { grid-template-columns: 1fr auto; gap: 10px; padding: 18px 0; }.intro-strip p { grid-column: 1 / -1; font-size: 13px; }.content-section { padding: 70px 0; }.section-heading { display: block; margin-bottom: 28px; }.section-heading > p { margin-top: 16px; }.research-grid, .problem-list, .feature-grid { grid-template-columns: 1fr; }.research-item { min-height: 0; border-right: 0; border-bottom: 1px solid var(--line); }.research-item:last-child { border-bottom: 0; }.research-item h3 { margin-top: 28px; }.research-item p { min-height: 0; }.tech-path { justify-content: flex-start; line-height: 2; }.problem-item { min-height: 0; }.feature-item { min-height: 0; }.outcome-section { padding: 50px 20px; }.outcome-grid { grid-template-columns: 1fr; }.outcome-grid span:nth-child(odd) { margin-right: 0; }.project-footer { align-items: flex-start; flex-direction: column; padding: 24px 14px 88px; }.project-footer > div { line-height: 1.7; } }
@media (prefers-reduced-motion: reduce) { .project-home *, .project-home *::before, .project-home *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }
</style>
