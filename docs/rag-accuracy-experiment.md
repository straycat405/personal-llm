# RAG 정확도 실험 결과

골든셋 35건 · 코퍼스: Spring AI 공식 레퍼런스 문서 7페이지 (Advisors/ChatClient/RAG/VectorStore/ETL/ChatMemory/Tools) · 임베딩: bge-m3

## 청크 크기별 Recall@k

| 청크 크기 | 청크 수 | 임베딩 소요(ms) | Recall@1 | Recall@3 | Recall@5 | Recall@10 | 운영설정 Recall(top5,th=0.5) | 평균 쿼리 지연(ms) |
|---|---|---|---|---|---|---|---|---|
| 800  | 66 | 31418 | 85.7% | 94.3% | 100.0% | 100.0% | 100.0% | 290 |
| 1500 (현재값) | 35 | 6555 | 74.3% | 91.4% | 94.3% | 100.0% | 88.6% | 51 |
| 3000  | 19 | 2483 | 65.7% | 91.4% | 97.1% | 100.0% | 82.9% | 52 |

## 난이도별 Recall@5

| 청크 크기 | easy | medium | hard |
|---|---|---|---|
| 800 | 100.0% | 100.0% | 100.0% |
| 1500 | 100.0% | 92.9% | 85.7% |
| 3000 | 100.0% | 92.9% | 100.0% |

## 측정 방법

- Recall@k: 골든셋 쿼리당 정답 문서가 1개뿐이라 Hit Rate@k와 동일 — top-k 검색 결과에 정답 문서의 청크가 하나라도 포함되면 hit
- Recall@1/3/5/10: similarityThreshold=0.0(필터 없이 순수 랭킹) 기준
- 운영설정 Recall: topK=5, similarityThreshold=0.5 — 실제 배포 중인 SearchRequest 설정과 동일
