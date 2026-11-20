/* 校园二手交易平台 · 原型公共脚本：图标、导航、假数据、通用交互 */

const ICONS = {
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
  message: '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
  user: '<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>',
  heart: '<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>',
  pin: '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10"/><path d="m9 12 2 2 4-4"/>',
  right: '<path d="m9 18 6-6-6-6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  left: '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  star: '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>',
  flag: '<path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><path d="M4 22v-7"/>',
  image: '<rect width="18" height="18" x="3" y="3" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21"/>',
  send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
  package: '<path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>',
  dashboard: '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
  audit: '<rect width="8" height="4" x="8" y="2" rx="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="m9 14 2 2 4-4"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  scale: '<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10"/><path d="M12 3v18"/><path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>',
  alert: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  sliders: '<path d="M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3M14 2v4M8 10v4M16 18v4"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',
  clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
  wallet: '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
  eye: '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
  book: '<path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"/>',
  phone: '<rect width="14" height="20" x="5" y="2" rx="2"/><path d="M12 18h.01"/>',
  laptop: '<path d="M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16"/>',
  headphones: '<path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"/>',
  cup: '<path d="M17 8h1a4 4 0 1 1 0 8h-1"/><path d="M3 8h14v9a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4Z"/><path d="M6 2v2M10 2v2M14 2v2"/>',
  shirt: '<path d="M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z"/>',
  bike: '<circle cx="18.5" cy="17.5" r="3.5"/><circle cx="5.5" cy="17.5" r="3.5"/><circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>',
  grid: '<rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/>',
  megaphone: '<path d="m3 11 18-5v12L3 14v-3z"/><path d="M11.6 16.8a3 3 0 1 1-5.8-1.6"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m17 8-5-5-5 5"/><path d="M12 3v12"/>',
  file: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M16 13H8M16 17H8M10 9H8"/>',
  trend: '<path d="m22 7-8.5 8.5-5-5L2 17"/><path d="M16 7h6v6"/>',
  mail: '<rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>',
  lock: '<rect width="18" height="11" x="3" y="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  ban: '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
  undo: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
  more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
  filter: '<path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
  trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  gift: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M12 8v13"/><path d="M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7"/><path d="M7.5 8a2.5 2.5 0 0 1 0-5C10 3 12 8 12 8s2-5 4.5-5a2.5 2.5 0 0 1 0 5"/>',
};

function icon(name, cls = '') {
  return `<svg class="icon ${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ''}</svg>`;
}

/* ---------- 假数据 ---------- */
const MODE = { 1: '仅面交', 2: '仅担保', 3: '面交/担保' };
const GOODS = [
  { title: '高等数学 第七版 上下册 同济 笔记少', price: 25, orig: 89.6, cond: '轻微使用', campus: '贝克兰德', mode: 1, icon: 'book', tint: 1, seller: '林晓', time: '10 分钟前', fav: 12 },
  { title: 'iPad Air 5 64G 蓝色 带笔 电池 92%', price: 2650, orig: 4399, cond: '几乎全新', campus: '廷根', mode: 3, icon: 'phone', tint: 2, seller: '数码小陈', time: '25 分钟前', fav: 48 },
  { title: '宿舍小台灯 三档调光 毕业清仓', price: 15, orig: 59, cond: '轻微使用', campus: '贝克兰德', mode: 1, icon: 'cup', tint: 3, seller: '大四学姐', time: '1 小时前', fav: 6 },
  { title: '索尼 WH-1000XM4 降噪耳机 国行', price: 980, orig: 1999, cond: '轻微使用', campus: '廷根', mode: 2, icon: 'headphones', tint: 4, seller: '阿杰', time: '2 小时前', fav: 31 },
  { title: '捷安特 ATX 山地车 送锁和打气筒', price: 450, orig: 1680, cond: '明显使用', campus: '贝克兰德', mode: 1, icon: 'bike', tint: 6, seller: '骑行社小王', time: '3 小时前', fav: 22 },
  { title: '考研数学 张宇 18 讲 + 1000 题 全新未拆', price: 60, orig: 139, cond: '全新', campus: '廷根', mode: 3, icon: 'book', tint: 1, seller: '上岸啦', time: '今天 09:12', fav: 19 },
  { title: '优衣库 摇粒绒外套 M 码 只穿过两次', price: 45, orig: 199, cond: '几乎全新', campus: '贝克兰德', mode: 1, icon: 'shirt', tint: 5, seller: '小鹿', time: '今天 08:40', fav: 9 },
  { title: 'ThinkPad X1 Carbon 2021 i7 16G 512G', price: 3800, orig: 9999, cond: '轻微使用', campus: '廷根', mode: 2, icon: 'laptop', tint: 2, seller: '计科老张', time: '昨天', fav: 57 },
  { title: '宿舍收纳箱 三个一起 免费送 自提', price: 0, orig: null, cond: '明显使用', campus: '贝克兰德', mode: 1, icon: 'gift', tint: 3, seller: '毕业生阿宁', time: '昨天', fav: 4 },
  { title: '羽毛球拍 尤尼克斯 弓箭 11 一支', price: 320, orig: 899, cond: '轻微使用', campus: '廷根', mode: 3, icon: 'bike', tint: 6, seller: '羽协会长', time: '2 天前', fav: 15 },
  { title: 'Kindle Paperwhite 5 8G 送保护套', price: 520, orig: 1068, cond: '几乎全新', campus: '贝克兰德', mode: 3, icon: 'book', tint: 4, seller: '读书人', time: '2 天前', fav: 26 },
  { title: '小米电饭煲 1.6L 宿舍可用 低功率', price: 70, orig: 169, cond: '轻微使用', campus: '廷根', mode: 1, icon: 'cup', tint: 5, seller: '干饭王', time: '3 天前', fav: 8 },
];

function priceHtml(p) {
  return p === 0 ? '<span class="price">免费送</span>' : `<span class="price"><small>¥</small>${p}</span>`;
}

function goodsCard(g) {
  const badges = [];
  if (g.mode !== 1) badges.push(`<span class="tag tag-green">${icon('shield', 'icon-sm')}担保</span>`);
  if (g.price === 0) badges.push('<span class="tag tag-orange">免费送</span>');
  return `<a class="goods-card" href="goods.html">
    <div class="goods-cover tint-${g.tint}">${icon(g.icon)}<div class="badge-pos">${badges.join('')}</div></div>
    <div class="goods-body">
      <div class="goods-title">${g.title}</div>
      <div class="row">${priceHtml(g.price)}${g.orig ? `<span class="strike">¥${g.orig}</span>` : ''}</div>
      <div class="goods-meta"><span class="seller"><span class="avatar avatar-sm">${g.seller[0]}</span><span>${g.seller}</span></span><span>${g.campus}</span></div>
    </div></a>`;
}

/* ---------- 用户端顶部导航 + 手机底部导航 ---------- */
function renderHeader() {
  const el = document.getElementById('site-header');
  if (!el) return;
  const page = document.body.dataset.page || '';
  const act = (p) => (p === page ? ' active' : '');
  el.outerHTML = `<header class="header"><div class="container">
      <a class="logo" href="home.html"><span class="logo-mark">${icon('package')}</span><span>WU 校园闲置</span></a>
      <form class="header-search" action="search.html" role="search">
        ${icon('search')}<input name="q" placeholder="搜索教材、数码、生活用品…" aria-label="搜索商品">
        <button class="btn btn-primary" type="submit">搜索</button>
      </form>
      <nav class="header-nav" aria-label="主导航">
        <a class="nav-link${act('home')}" href="home.html">首页</a>
        <a class="nav-link${act('want')}" href="want.html">求购广场</a>
        <a class="nav-link${act('chat')}" href="chat.html" aria-label="消息">${icon('message')}<span class="dot-badge">3</span></a>
        <a class="nav-link${act('notice')}" href="notifications.html" aria-label="通知">${icon('bell')}<span class="dot-badge">5</span></a>
        <a class="btn btn-primary" href="publish.html">${icon('plus')}发布闲置</a>
        <a class="nav-link${act('me')}" href="me.html" aria-label="个人中心"><span class="avatar">林</span></a>
      </nav>
    </div></header>`;
  const bar = document.createElement('nav');
  bar.className = 'tabbar';
  bar.setAttribute('aria-label', '底部导航');
  bar.innerHTML = `
    <a class="${act('home')}" href="home.html">${icon('home')}首页</a>
    <a class="${act('want')}" href="want.html">${icon('megaphone')}求购</a>
    <a class="publish" href="publish.html">${icon('plus')}发布</a>
    <a class="${act('chat')}" href="chat.html">${icon('message')}消息<span class="dot-badge">3</span></a>
    <a class="${act('me')}" href="me.html">${icon('user')}我的</a>`;
  document.body.appendChild(bar);
}

/* ---------- 后台侧边栏 ---------- */
function renderAdminSide() {
  const el = document.getElementById('admin-side');
  if (!el) return;
  const page = document.body.dataset.page || '';
  const items = [
    ['总览', [['dashboard', 'dashboard', '数据看板']]],
    ['审核', [['audit', 'audit', '商品审核', 12]]],
    ['管理', [['users', 'users', '用户管理'], ['disputes', 'scale', '订单与纠纷', 3], ['reports', 'flag', '举报处理', 5]]],
    ['运营', [['content', 'sliders', '内容运营']]],
    ['系统', [['system', 'lock', '权限与日志']]],
  ];
  el.outerHTML = `<aside class="admin-side"><div class="logo" title="管理后台"><span class="logo-mark">${icon('package')}</span><span class="label">管理后台</span></div>
    ${items.map(([g, links]) => `<div class="group">${g}</div>` + links.map(([id, ic, name, c]) =>
      `<a href="${id}.html" class="${id === page ? 'active' : ''}" title="${name}" aria-label="${name}${c ? `，${c} 条待处理` : ''}">${icon(ic)}<span class="label">${name}</span>${c ? `<span class="count" aria-hidden="true">${c}</span>` : ''}</a>`).join('')).join('')}
  </aside>`;
}

/* ---------- 通用交互 ---------- */
function toast(text) {
  let t = document.querySelector('.toast');
  if (!t) { t = document.createElement('div'); t.className = 'toast'; t.setAttribute('role', 'status'); document.body.appendChild(t); }
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), 1800);
}

document.addEventListener('click', (e) => {
  const tab = e.target.closest('.tab[data-tab]');
  if (tab) {
    const scope = tab.closest('[data-tabs]');
    scope.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
    scope.querySelectorAll('.tab-panel').forEach((p) => p.classList.toggle('active', p.dataset.panel === tab.dataset.tab));
  }
  const single = e.target.closest('[data-single] > .chip, [data-single] > .radio-card');
  if (single) single.parentElement.querySelectorAll(':scope > .chip, :scope > .radio-card').forEach((c) => c.classList.toggle('active', c === single));
  const open = e.target.closest('[data-open]');
  if (open) { e.preventDefault(); document.getElementById(open.dataset.open).classList.add('open'); }
  if (e.target.closest('[data-close]') || e.target.classList.contains('modal-mask')) {
    e.target.closest('.modal-mask')?.classList.remove('open');
  }
  const tst = e.target.closest('[data-toast]');
  if (tst) { e.preventDefault(); toast(tst.dataset.toast); }
  const fav = e.target.closest('[data-fav]');
  if (fav) { fav.classList.toggle('active'); toast(fav.classList.contains('active') ? '已收藏' : '已取消收藏'); }
  const thumb = e.target.closest('[data-thumb]');
  if (thumb) {
    const main = document.getElementById('gallery-main');
    main.className = `gallery-main goods-cover tint-${thumb.dataset.thumb}`;
    thumb.parentElement.querySelectorAll('[data-thumb]').forEach((t) => t.classList.toggle('active', t === thumb));
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') document.querySelectorAll('.modal-mask.open').forEach((m) => m.classList.remove('open'));
});

/* ---------- 启动 ---------- */
renderHeader();
renderAdminSide();
document.querySelectorAll('[data-icon]').forEach((el) => { el.outerHTML = icon(el.dataset.icon, el.className); });
document.querySelectorAll('[data-goods]').forEach((el) => {
  const [from, to] = (el.dataset.goods || '0,12').split(',').map(Number);
  el.innerHTML = GOODS.slice(from, to).map(goodsCard).join('');
});
