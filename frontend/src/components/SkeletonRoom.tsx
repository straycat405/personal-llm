// [역할] 채팅방 목록 로딩 중 shimmer 자리표시자
export default function SkeletonRoom() {
  return (
    <div className="px-3 py-2.5 rounded-lg animate-pulse">
      <div className="h-3.5 bg-gray-800 rounded w-3/4 mb-1.5" />
      <div className="h-2.5 bg-gray-800/60 rounded w-1/2" />
    </div>
  )
}
