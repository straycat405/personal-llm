/**
 * 내보내기 전용 페이지 분리 — Figma Scripter 스크립트
 *
 * [왜 필요한가]
 *   File → Export frames to PDF 는 "현재 페이지의 모든 최상위 프레임"을 대상으로 한다.
 *   참고용으로 열어둔 기존 디자인이나 실험용 프레임이 같은 페이지에 남아 있으면
 *   그것까지 PDF에 페이지로 끼어든다. 이 스크립트는 1920×1080 슬라이드만
 *   전용 페이지로 옮겨 그 사고를 막는다.
 *
 * [실행] Scripter에 붙여넣고 실행 → 자동으로 새 페이지로 이동한다.
 *        그 상태에서 File → Export frames to PDF 를 누르면 된다.
 *
 * [주의] 프레임을 "이동"시킨다(복제 아님). 원래 페이지에서는 사라진다.
 *        되돌리려면 Figma에서 Cmd/Ctrl+Z.
 */

const PAGE_NAME = 'Portfolio Export';
const W = 1920, H = 1080;

// 현재 페이지에서 슬라이드만 고른다
const slides = figma.currentPage.children.filter(
  (n) => n.type === 'FRAME' && n.width === W && n.height === H
);
if (!slides.length) throw new Error('현재 페이지에 1920×1080 슬라이드가 없습니다.');

// 내보내기 순서는 캔버스 위치를 따르므로, 옮길 때도 그 순서를 유지한다
slides.sort((a, b) => (a.y - b.y) || (a.x - b.x));

// 같은 이름의 페이지가 이미 있으면 덮어쓰지 않고 새로 만든다 (이전 결과 보존)
const existingNames = figma.root.children.map((p) => p.name);
let name = PAGE_NAME;
let n = 2;
while (existingNames.includes(name)) { name = `${PAGE_NAME} ${n++}`; }

const page = figma.createPage();
page.name = name;

// 옮기기 전 위치를 기억해 두고 그대로 재현한다 (한 줄 배치가 흐트러지지 않도록)
const positions = slides.map((s) => ({ node: s, x: s.x, y: s.y }));
positions.forEach((p) => {
  page.appendChild(p.node);
  p.node.x = p.x;
  p.node.y = p.y;
});

await figma.setCurrentPageAsync(page);
figma.currentPage.selection = slides;
figma.viewport.scrollAndZoomIntoView(slides);

`✅ 슬라이드 ${slides.length}장을 "${name}" 페이지로 옮겼습니다.

이제 이 페이지에서:
  File → Export frames to PDF
→ ${slides.length}페이지짜리 PDF 한 개가 만들어집니다.

첫 장: ${slides[0].name}
끝 장: ${slides[slides.length - 1].name}`
