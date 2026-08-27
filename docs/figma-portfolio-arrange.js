/**
 * 포트폴리오 슬라이드 순서 정렬 — Figma Scripter 스크립트
 *
 * [왜 필요한가]
 *   Figma가 PDF로 내보낼 때는 프레임의 캔버스 위치(위→아래, 왼→오른쪽)를 따른다.
 *   여러 스크립트로 나눠 만들면 각자 "기존 콘텐츠 오른쪽"에 붙어서 실제 순서와
 *   위치가 어긋난다(예: 클로징이 두 프로젝트 사이에 끼는 상황).
 *   이 스크립트는 아래 ORDER대로 슬라이드를 한 줄에 다시 늘어놓는다.
 *
 * [실행] Scripter에 붙여넣고 실행. 프레임을 이동만 하며 내용은 건드리지 않는다.
 *
 * [순서 바꾸기] ORDER 배열의 줄 순서만 바꾸면 된다.
 *   - 일반 백엔드 지원  : First Ticket 먼저 (아래 기본값)
 *   - AI/LLM 직무 지원 : PERSONAL_RAG 블록을 FIRST_TICKET 블록보다 위로 옮긴다
 */

// ── 정렬 순서 정의 ────────────────────────────────────────────
// 각 항목은 프레임 이름의 시작 문자열. 같은 접두사를 가진 프레임은 이름 오름차순으로 들어간다.
const FIRST_TICKET  = 'FT ';   // FT 01 ~ FT 07
const PERSONAL_RAG  = /^\d\d · /; // 01 · ~ 08 · (00/99는 아래에서 따로 잡는다)

const ORDER = [
  { label: '통합 표지',      match: (n) => n.name.startsWith('00 · ') },
  { label: 'First Ticket',  match: (n) => n.name.startsWith(FIRST_TICKET) },
  { label: 'Personal RAG',  match: (n) => PERSONAL_RAG.test(n.name)
                                          && !n.name.startsWith('00 · ')
                                          && !n.name.startsWith('99 · ') },
  { label: '클로징',         match: (n) => n.name.startsWith('99 · ') },
];

// ── 배치 설정 ─────────────────────────────────────────────────
const W = 1920, H = 1080;
const GAP = 100;          // 슬라이드 사이 간격
const GROUP_GAP = 260;    // 그룹(프로젝트) 사이 간격 — 눈으로 구분되게 넓힌다
const ROW_MAX = 0;        // 0 = 한 줄로 전부. N을 주면 N장마다 줄바꿈한다.

// ── 대상 수집 ─────────────────────────────────────────────────
const slides = figma.currentPage.children.filter(
  (n) => n.type === 'FRAME' && n.width === W && n.height === H
);
if (!slides.length) throw new Error('1920×1080 슬라이드를 찾지 못했습니다.');

const originX = Math.min.apply(null, slides.map((n) => n.x));
const originY = Math.min.apply(null, slides.map((n) => n.y));

// ── 순서대로 나열 ─────────────────────────────────────────────
const ordered = [];
const groupSizes = [];
const used = new Set();

for (const group of ORDER) {
  const picked = slides
    .filter((n) => !used.has(n.id) && group.match(n))
    .sort((a, b) => a.name.localeCompare(b.name, 'en'));
  picked.forEach((n) => used.add(n.id));
  groupSizes.push({ label: group.label, count: picked.length });
  ordered.push(...picked);
}

// ORDER 어디에도 안 걸린 슬라이드는 맨 뒤에 붙여 눈에 띄게 둔다 (조용히 사라지지 않도록)
const orphans = slides.filter((n) => !used.has(n.id));
ordered.push(...orphans);

// ── 위치 적용 ─────────────────────────────────────────────────
let col = 0, row = 0, groupIdx = 0, inGroup = 0, extraX = 0;
for (let i = 0; i < ordered.length; i++) {
  // 그룹이 바뀌는 지점에서 간격을 한 번 더 벌린다
  while (groupIdx < groupSizes.length && inGroup >= groupSizes[groupIdx].count) {
    groupIdx++; inGroup = 0;
    if (groupIdx < groupSizes.length) extraX += GROUP_GAP - GAP;
  }
  const n = ordered[i];
  n.x = originX + col * (W + GAP) + extraX;
  n.y = originY + row * (H + GAP);
  col++; inGroup++;
  if (ROW_MAX > 0 && col >= ROW_MAX) { col = 0; row++; extraX = 0; }
}

figma.currentPage.selection = ordered;
figma.viewport.scrollAndZoomIntoView(ordered);

const summary = groupSizes.map((g) => `  ${g.label}: ${g.count}장`).join('\n');
`✅ ${ordered.length}장 정렬 완료 (좌 → 우 한 줄)
${summary}${orphans.length ? `\n  ⚠ 분류 안 됨(맨 뒤로): ${orphans.map((n) => n.name).join(', ')}` : ''}

PDF로 내보낼 때 이 좌우 순서가 곧 페이지 순서가 된다.`
