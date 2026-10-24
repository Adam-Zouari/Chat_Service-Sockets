#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: bash scripts/build.sh [--test]\n'
    printf 'Compile Java 11 application classes; --test also compiles and runs all tests.\n'
}

run_tests=false
if (( $# > 1 )); then
    usage >&2
    exit 2
fi
case "${1:-}" in
    '') ;;
    --test) run_tests=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac

project_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_root"

command -v javac >/dev/null || { printf 'JDK 11 or newer is required (javac not found).\n' >&2; exit 1; }
if "$run_tests"; then
    command -v java >/dev/null || { printf 'Java is required to run tests (java not found).\n' >&2; exit 1; }
fi

# Native Java on Windows needs a semicolon classpath and Windows-form paths.
classpath_separator=':'
windows_java=false
case "$(uname -s)" in
    CYGWIN*|MINGW*|MSYS*) classpath_separator=';'; windows_java=true ;;
esac
java_path() {
    if "$windows_java"; then
        cygpath -m "$1"
    else
        printf '%s\n' "$1"
    fi
}

main_sources=()
while IFS= read -r source; do
    main_sources+=("$(java_path "$source")")
done < <(find "$project_root/src/main/java" -type f -name '*.java' | LC_ALL=C sort)
if (( ${#main_sources[@]} == 0 )); then
    printf 'No Java sources found in src/main/java.\n' >&2
    exit 1
fi
main_output="$(java_path "$project_root/build/classes/main")"
mkdir -p -- "$project_root/build/classes/main"
javac --release 11 -encoding UTF-8 -Xlint:all -d "$main_output" "${main_sources[@]}"
printf 'Compiled application classes to build/classes/main.\n'

if "$run_tests"; then
    test_sources=()
    while IFS= read -r source; do
        test_sources+=("$(java_path "$source")")
    done < <(find "$project_root/src/test/java" -type f -name '*.java' | LC_ALL=C sort)
    if (( ${#test_sources[@]} == 0 )); then
        printf 'No Java tests found in src/test/java.\n' >&2
        exit 1
    fi
    test_output="$(java_path "$project_root/build/classes/test")"
    mkdir -p -- "$project_root/build/classes/test"
    javac --release 11 -encoding UTF-8 -Xlint:all -cp "$main_output" -d "$test_output" "${test_sources[@]}"
    test_classpath="${main_output}${classpath_separator}${test_output}"
    for test_class in \
        chat.server.ChatServerTest \
        chat.server.ChatStoreTest \
        chat.client.ChatConnectionTest \
        chat.client.ChatClientFormTest \
        chat.client.ChatViewTest \
        chat.server.ChatSessionTest; do
        printf 'Running %s\n' "$test_class"
        java -Djava.awt.headless=true -cp "$test_classpath" "$test_class"
    done
fi
