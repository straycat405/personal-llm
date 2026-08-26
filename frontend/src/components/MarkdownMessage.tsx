import {
  isValidElement,
  useEffect,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

function nodeToText(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(nodeToText).join('')
  if (isValidElement<{ children?: ReactNode }>(node)) return nodeToText(node.props.children)
  return ''
}

function CodeBlock({ children }: ComponentPropsWithoutRef<'pre'>) {
  const [copied, setCopied] = useState(false)
  const resetTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const code = nodeToText(children).replace(/\n$/, '')
  const language = isValidElement<{ className?: string }>(children)
    ? children.props.className?.replace(/^language-/, '')
    : undefined

  useEffect(() => () => {
    if (resetTimerRef.current) clearTimeout(resetTimerRef.current)
  }, [])

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      if (resetTimerRef.current) clearTimeout(resetTimerRef.current)
      resetTimerRef.current = setTimeout(() => setCopied(false), 1600)
    } catch {
      // 보안 컨텍스트·권한 설정 때문에 Clipboard API가 거부돼도 UI 이벤트를 깨뜨리지 않는다.
      setCopied(false)
    }
  }

  return (
    <div className="not-prose my-4 overflow-hidden rounded-xl border border-gray-700 bg-[#0d1117] shadow-sm">
      <div className="flex h-9 items-center justify-between border-b border-gray-700 bg-gray-900/90 px-3">
        <span className="text-[11px] font-medium uppercase tracking-wide text-gray-500">
          {language || 'code'}
        </span>
        <button
          type="button"
          onClick={() => void handleCopy()}
          className="rounded-md px-2 py-1 text-xs text-gray-400 transition hover:bg-gray-800 hover:text-gray-200"
          aria-label="코드 복사"
        >
          {copied ? '복사됨' : '복사'}
        </button>
      </div>
      <pre className="m-0 overflow-x-auto p-4 text-[13px] leading-6 text-gray-200">
        <code>{code}</code>
      </pre>
    </div>
  )
}

function MarkdownTable({ children, ...props }: ComponentPropsWithoutRef<'table'>) {
  return (
    <div className="not-prose my-4 overflow-x-auto rounded-xl border border-gray-700">
      <table {...props} className="w-full min-w-[520px] border-collapse text-left text-sm">
        {children}
      </table>
    </div>
  )
}

export default function MarkdownMessage({ content }: { content: string }) {
  return (
    <div
      className="prose prose-invert max-w-none text-[15px] leading-7 text-gray-200
                 prose-headings:mb-3 prose-headings:mt-6 prose-headings:font-semibold prose-headings:tracking-tight prose-headings:text-gray-50
                 prose-h1:text-xl prose-h2:text-lg prose-h3:text-base
                 prose-p:my-3 prose-p:leading-7
                 prose-ul:my-3 prose-ol:my-3 prose-li:my-1 prose-li:marker:text-gray-500
                 prose-strong:font-semibold prose-strong:text-gray-50
                 prose-code:rounded-md prose-code:bg-gray-900 prose-code:px-1.5 prose-code:py-0.5 prose-code:text-[0.88em] prose-code:font-medium prose-code:text-violet-300
                 prose-code:before:content-none prose-code:after:content-none
                 prose-a:text-violet-400 prose-a:underline-offset-4 hover:prose-a:text-violet-300
                 prose-hr:my-6 prose-hr:border-gray-700
                 prose-blockquote:my-4 prose-blockquote:border-violet-500/70 prose-blockquote:bg-violet-950/20 prose-blockquote:py-1 prose-blockquote:pr-4 prose-blockquote:text-gray-300"
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          pre: CodeBlock,
          table: MarkdownTable,
          thead: ({ children, ...props }) => (
            <thead {...props} className="bg-gray-900 text-gray-200">{children}</thead>
          ),
          th: ({ children, ...props }) => (
            <th {...props} className="border-b border-gray-700 px-3 py-2.5 font-semibold">{children}</th>
          ),
          td: ({ children, ...props }) => (
            <td {...props} className="border-b border-gray-800 px-3 py-2.5 align-top text-gray-300">{children}</td>
          ),
          a: ({ children, ...props }) => (
            <a {...props} target="_blank" rel="noreferrer noopener">{children}</a>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
