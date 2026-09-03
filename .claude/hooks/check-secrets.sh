#!/usr/bin/env bash
# PreToolUse 훅: git commit 실행 전, staged diff에 새로 추가된 비밀정보 의심 라인이 있으면 커밋을 막는다.
# 이 저장소는 application.yml에 실 DB 비밀번호/API 키가 이미 커밋되어 있어(알려진 이슈),
# 기존 노출을 다시 지적하는 대신 "새로 추가되는" 라인만 검사한다.

input="$(cat)"

case "$input" in
  *'git commit'*) ;;
  *) exit 0 ;;
esac

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  exit 0
fi

diff="$(git diff --cached -U0 2>/dev/null)"
if [ -z "$diff" ]; then
  exit 0
fi

hit="$(printf '%s\n' "$diff" | grep -Ei '^\+[^+].*(password|secret|api[_-]?key|access[_-]?key|private[_-]?key|service[_-]?key)[[:space:]]*[:=]')"

if [ -n "$hit" ]; then
  {
    echo "[check-secrets] staged 변경분에 비밀정보로 의심되는 라인이 있어 커밋을 막았습니다:"
    printf '%s\n' "$hit"
    echo "실제 키/비밀번호라면 커밋에서 제외하거나 환경변수/시크릿 관리로 옮기세요."
    echo "오탐이면 해당 파일을 git add에서 제외하고 필요한 부분만 커밋하세요."
  } >&2
  exit 2
fi

exit 0
