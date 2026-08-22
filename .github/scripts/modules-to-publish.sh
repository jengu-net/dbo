#!/usr/bin/env bash
# Which library modules actually need publishing.
#
# The repository receives ~242MB on every push to main, and almost none of it
# has changed: the FHIR stack alone is 88MB and moves a few times a year. This
# asks, per module, whether the jar we just built is the jar the repository
# already serves, and prints the publish tasks for the ones where it is not.
#
# The question is answerable only because jars are reproducible (timestamps
# off, stable entry order) — without that every rebuild differs and every
# module always looks changed.
#
# It compares CHECKSUMS rather than git history on purpose. A fat bundle
# embeds other modules and computes its manifest from a sibling's exports, so
# it changes when its own sources did not; a source diff would skip exactly
# the artifacts most likely to be stale.
#
# Every uncertainty publishes. No metadata, no checksum, unreachable
# repository, unreadable answer: publish. The cost of a needless upload is
# minutes; the cost of a skipped one is a consumer resolving a stale jar and
# nobody knowing why.
set -uo pipefail

BASE="${DBO_REPO_BASE:-https://repo.jengu.cloud/repository/maven-snapshots}"
GROUP_PATH="cloud/jengu/dbo"
tasks=()
unchanged=()

# Which modules publish at all is Gradle's answer, not a directory glob:
# the notALibrary list lives in the build, and a second copy here would be
# wrong the first time a module is added.
while read -r task; do
  [ -n "$task" ] || continue
  project="${task%:publishAllPublicationsToJenguRepoRepository}"
  module="${project##*:}"
  dir="${project#:}"; dir="${dir//://}"

  jar=""
  for candidate in "$dir"/build/libs/"$module"-*.jar; do
    case "$candidate" in *-sources.jar|*-javadoc.jar) continue ;; esac
    [ -e "$candidate" ] && jar="$candidate" && break
  done
  # No jar to compare is not a reason to skip: publish and let the build say
  # what it thinks it is publishing.
  if [ -z "$jar" ]; then
    tasks+=("$task")
    continue
  fi

  file="$(basename "$jar")"
  version="${file#"$module"-}"; version="${version%.jar}"

  # A release version is published once and never rewritten, so there is
  # nothing to compare and nothing to skip.
  case "$version" in *SNAPSHOT) ;; *) tasks+=("$task"); continue ;; esac

  meta="$BASE/$GROUP_PATH/$module/$version/maven-metadata.xml"
  resolved="$(curl -fsSL --max-time 20 "$meta" 2>/dev/null \
      | sed -n 's:.*<value>\(.*\)</value>.*:\1:p' | head -1)"
  if [ -z "$resolved" ]; then
    tasks+=("$task")
    continue
  fi

  remote="$(curl -fsSL --max-time 20 \
      "$BASE/$GROUP_PATH/$module/$version/$module-$resolved.jar.sha1" 2>/dev/null \
      | tr -d '[:space:]')"
  local_sha="$(shasum -a 1 "$jar" | cut -d' ' -f1)"

  if [ -n "$remote" ] && [ "$remote" = "$local_sha" ]; then
    unchanged+=("$module")
  else
    tasks+=("$task")
  fi
done < <(./gradlew -q listPublishTasks 2>/dev/null)

if [ ${#unchanged[@]} -gt 0 ]; then
  echo "unchanged, not republished: ${unchanged[*]}" >&2
fi
if [ ${#tasks[@]} -eq 0 ]; then
  echo "nothing to publish — every module matches what the repository serves" >&2
else
  echo "publishing: ${#tasks[@]} module(s)" >&2
fi
printf '%s\n' "${tasks[@]+"${tasks[@]}"}"
