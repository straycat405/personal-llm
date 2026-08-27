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

// tag = 리네임 시 이름에 들어갈 프로젝트 표시명.
// match는 리네임 전(원래 이름)과 후(통번호가 붙은 이름) 양쪽을 모두 인식해야
// 이 스크립트도, 각 생성 스크립트도 재실행이 안전하다.
const ORDER = [
  { label: '통합 표지', tag: 'Portfolio',
    match: (n) => n.name.startsWith('00 · ') || / · Portfolio · Cover/i.test(n.name) },
  { label: 'First Ticket', tag: 'First Ticket',
    match: (n) => n.name.startsWith(FIRST_TICKET) || / · First Ticket · /.test(n.name) },
  { label: 'Personal RAG', tag: 'Personal RAG',
    match: (n) => / · Personal RAG · /.test(n.name)
                  || (PERSONAL_RAG.test(n.name)
                      && !/First Ticket|Portfolio/.test(n.name)
                      && !n.name.startsWith('00 · ')
                      && !n.name.startsWith('99 · ')) },
  { label: '클로징', tag: 'Portfolio',
    match: (n) => n.name.startsWith('99 · ') || / · Portfolio · Closing/i.test(n.name) },
];

// ── 배치 설정 ─────────────────────────────────────────────────
const W = 1920, H = 1080;
const GAP = 100;          // 슬라이드 사이 간격
const GROUP_GAP = 260;    // 그룹(프로젝트) 사이 간격 — 눈으로 구분되게 넓힌다
const ROW_MAX = 0;        // 0 = 한 줄로 전부. N을 주면 N장마다 줄바꿈한다.

/**
 * 최종 순서대로 이름 앞에 통번호를 붙인다 (01 · … ~ 17 · …).
 * 프레임을 개별 PDF로 내보내면 파일명이 곧 프레임 이름이라, 번호가 있어야
 * 파일명 정렬만으로 병합 순서가 맞는다.
 *
 * [형식] "NN · <프로젝트> · <원래 제목>"
 *   프로젝트명을 넣는 이유: 통번호만 붙이면 "02 · COVER"가 어느 덱 표지인지
 *   알 수 없고, 각 생성 스크립트가 재실행 때 자기 슬라이드를 식별할 근거도 사라진다.
 *
 * [멱등] 이미 붙은 번호와 프로젝트명은 벗겨내고 다시 붙이므로 여러 번 돌려도 누적되지 않는다.
 */
const RENAME = true;

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
  groupSizes.push({ label: group.label, tag: group.tag, count: picked.length });
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

// ── 통번호 리네임 ─────────────────────────────────────────────
const renamed = [];
if (RENAME) {
  // ordered와 같은 길이로 각 슬라이드의 tag를 펼친다
  const tags = [];
  groupSizes.forEach((g) => { for (let i = 0; i < g.count; i++) tags.push(g.tag); });
  while (tags.length < ordered.length) tags.push(null); // ORDER에 안 걸린 슬라이드

  ordered.forEach((n, i) => {
    // 이전 실행이 붙인 번호와 프로젝트명을 먼저 벗겨낸다 (여러 번 돌려도 누적되지 않게)
    let title = n.name.replace(/^(FT )?\d{2,} · /, '');
    title = title.replace(/^(Portfolio|First Ticket|Personal RAG) · /, '');

    const no = String(i + 1).padStart(2, '0');
    const tag = tags[i];
    const next = tag ? no + ' · ' + tag + ' · ' + title : no + ' · ' + title;
    n.name = next;
    renamed.push(next);
  });
}

figma.currentPage.selection = ordered;
figma.viewport.scrollAndZoomIntoView(ordered);

const summary = groupSizes.map((g) => '  ' + g.label + ': ' + g.count + '장').join('\n');
const orphanLine = orphans.length
  ? '\n  ⚠ 분류 안 됨(맨 뒤로): ' + orphans.map((n) => n.name).join(', ')
  : '';
const renameLine = RENAME ? '\n\n[리네임]\n' + renamed.map((x) => '  ' + x).join('\n') : '';

'✅ ' + ordered.length + '장 정렬 완료 (좌 → 우 한 줄)\n'
  + summary + orphanLine + renameLine
  + '\n\n프레임을 개별 PDF로 내보내면 파일명이 이 이름이 되므로, 파일명 정렬 = 병합 순서다.'
