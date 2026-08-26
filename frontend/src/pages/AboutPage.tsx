import { Link } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'

/**
 * [역할] 프로젝트 소개 + 사용 가이드 (공개 라우트 /about)
 *
 * [설계 결정사항]
 * - 로그인 없이 접근 가능: 포트폴리오 리뷰어가 계정 없이도 이 프로젝트가 무엇이고
 *   어떤 기술로 만들어졌는지 파악할 수 있어야 한다. 로그인 후 모달로 두면 그게 불가능하다.
 * - 사용 순서를 명시한 이유: RAG 기반 답변을 받으려면 "문서를 먼저 인덱싱해야 한다"는
 *   전제가 있는데, 이를 모르면 AI가 문서를 모른다고 답하는 걸 버그로 오해하게 된다.
 * - 한계 명시: 로컬 GPU 1대로 추론하므로 응답이 느리고 동시 사용에 취약하다.
 *   숨기기보다 밝히는 편이 신뢰를 준다.
 */
export default function AboutPage() {
  const { isAuthenticated } = useAuthStore()

  return (
    <div className="min-h-screen bg-gray-950 text-gray-200">
      {/* 상단바 */}
      <header className="border-b border-gray-800 sticky top-0 bg-gray-950/95 backdrop-blur z-10">
        <div className="max-w-3xl mx-auto px-6 py-4 flex items-center gap-3">
          <span className="font-bold text-violet-400 text-lg">BTLLM</span>
          <span className="text-xs text-gray-600">사용 가이드</span>
          <Link
            to={isAuthenticated() ? '/chat' : '/login'}
            className="ml-auto text-sm bg-violet-700 hover:bg-violet-600 text-white
                       rounded-lg px-3 py-1.5 transition font-medium"
          >
            {isAuthenticated() ? '채팅으로' : '시작하기'}
          </Link>
        </div>
      </header>

      <main className="max-w-3xl mx-auto px-6 py-10 space-y-12">

        {/* 소개 */}
        <section>
          <h1 className="text-3xl font-light text-white mb-3">
            내 문서를 아는 <span className="text-violet-400">로컬 AI 챗봇</span>
          </h1>
          <p className="text-gray-400 leading-relaxed">
            PDF·워드·엑셀 같은 문서나 웹 페이지를 올려두면, AI가 그 내용을 근거로 답합니다.
            외부 API 없이 <strong className="text-gray-200">내 PC의 로컬 모델</strong>로 돌릴 수 있어
            문서가 밖으로 나가지 않습니다. 필요하면 Claude·Gemini 같은 상용 모델로 전환할 수도 있습니다.
          </p>
        </section>

        {/* 사용 순서 */}
        <section>
          <h2 className="text-lg font-semibold text-white mb-4">사용 순서</h2>
          <ol className="space-y-4">
            {[
              {
                title: '회원가입 후 로그인',
                body: '대화 내역은 계정별로 저장됩니다.',
              },
              {
                title: '문서 추가하기',
                body: '왼쪽 사이드바 "지식베이스" 옆 + 버튼을 누르면 URL 크롤링 또는 파일 업로드로 문서를 넣을 수 있습니다. PDF·DOCX·XLSX·PPTX·TXT를 지원하며, 인덱싱 진행률이 실시간으로 표시됩니다.',
              },
              {
                title: '질문하기',
                body: '문서 내용에 대해 물으면 AI가 지식베이스를 검색해 근거를 찾아 답합니다. 일반 대화는 검색 없이 바로 답합니다.',
              },
              {
                title: '문서 관리',
                body: '사이드바 "지식베이스"를 펼치면 현재 AI가 참조할 수 있는 문서 목록과 청크 수가 보이고, 개별 삭제도 가능합니다.',
              },
            ].map((step, i) => (
              <li key={step.title} className="flex gap-4">
                <span className="shrink-0 w-7 h-7 rounded-full bg-violet-900/60 text-violet-300
                                 text-sm flex items-center justify-center font-medium">
                  {i + 1}
                </span>
                <div>
                  <p className="text-gray-100 font-medium mb-0.5">{step.title}</p>
                  <p className="text-sm text-gray-500 leading-relaxed">{step.body}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        {/* 기능 */}
        <section>
          <h2 className="text-lg font-semibold text-white mb-4">주요 기능</h2>
          <div className="grid sm:grid-cols-2 gap-3">
            {[
              { name: '문서 기반 답변 (RAG)', desc: '업로드한 문서를 의미 단위로 쪼개 벡터로 저장하고, 질문과 관련된 부분만 찾아 근거로 씁니다.' },
              { name: '멀티 모델 전환', desc: '로컬 Ollama와 Claude·Gemini를 사이드바에서 바로 바꿔가며 쓸 수 있습니다.' },
              { name: '도구 사용 (Tool Calling)', desc: 'AI가 필요에 따라 웹 크롤링·지난 대화 검색·토큰 사용량 조회를 스스로 호출합니다.' },
              { name: '실시간 스트리밍', desc: 'WebSocket으로 답변이 생성되는 대로 표시되고, 연결이 끊기면 자동으로 재연결합니다.' },
            ].map((f) => (
              <div key={f.name} className="bg-gray-900 border border-gray-800 rounded-xl p-4">
                <p className="text-sm text-gray-100 font-medium mb-1">{f.name}</p>
                <p className="text-xs text-gray-500 leading-relaxed">{f.desc}</p>
              </div>
            ))}
          </div>
        </section>

        {/* 기술 스택 */}
        <section>
          <h2 className="text-lg font-semibold text-white mb-4">기술 스택</h2>
          <div className="text-sm text-gray-400 space-y-2">
            <p><span className="text-gray-500 inline-block w-20">백엔드</span> Java 17, Spring Boot 3.5, Spring AI 1.1.6, Spring Security</p>
            <p><span className="text-gray-500 inline-block w-20">AI</span> Ollama (qwen3:8b), pgVector, bge-m3 임베딩</p>
            <p><span className="text-gray-500 inline-block w-20">프론트엔드</span> React 19, TypeScript, Vite, Tailwind CSS</p>
            <p><span className="text-gray-500 inline-block w-20">인프라</span> PostgreSQL 17, Docker Compose, Prometheus·Grafana·Loki, k6</p>
          </div>
        </section>

        {/* 알아두면 좋은 점 */}
        <section>
          <h2 className="text-lg font-semibold text-white mb-4">알아두면 좋은 점</h2>
          <ul className="space-y-2.5 text-sm text-gray-500 leading-relaxed">
            <li className="flex gap-2">
              <span className="text-gray-700 shrink-0">•</span>
              <span>
                기본 모델은 <strong className="text-gray-300">내 PC GPU에서 직접 추론</strong>합니다.
                그래픽 메모리가 부족하거나 여러 명이 동시에 쓰면 응답이 눈에 띄게 느려집니다.
                빠른 응답이 필요하면 사이드바에서 Claude·Gemini로 바꿔보세요 (API 키 설정 필요).
              </span>
            </li>
            <li className="flex gap-2">
              <span className="text-gray-700 shrink-0">•</span>
              <span>
                AI가 문서를 모른다고 답한다면 사이드바 "지식베이스"를 펼쳐 해당 문서가 실제로
                인덱싱돼 있는지 먼저 확인해보세요.
              </span>
            </li>
            <li className="flex gap-2">
              <span className="text-gray-700 shrink-0">•</span>
              <span>
                자바스크립트로 그려지는 웹 페이지는 크롤링되지 않을 수 있습니다.
                그런 경우 내용을 파일로 저장해 업로드하는 편이 확실합니다.
              </span>
            </li>
          </ul>
        </section>

        <footer className="border-t border-gray-800 pt-6 pb-4 flex items-center gap-4">
          <Link
            to={isAuthenticated() ? '/chat' : '/login'}
            className="text-sm bg-violet-700 hover:bg-violet-600 text-white
                       rounded-lg px-4 py-2 transition font-medium"
          >
            {isAuthenticated() ? '채팅으로 돌아가기' : '시작하기'}
          </Link>
          <a
            href="https://github.com/straycat405/personal-llm"
            target="_blank"
            rel="noreferrer"
            className="text-sm text-gray-500 hover:text-gray-300 transition"
          >
            GitHub 저장소 →
          </a>
        </footer>
      </main>
    </div>
  )
}
