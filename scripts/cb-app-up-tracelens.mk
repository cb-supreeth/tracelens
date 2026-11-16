# Include from chargebee-app/Makefile after its normal up target.
# Existing checkouts are reused as-is: no pull, reset, or branch switch.
export TRACELENS_HOME ?= $(abspath ../cb-tracelens)
export TRACELENS_REPO ?= git@github.com:chargebee-tech/cb-tracelens.git
export TRACELENS_PYTHON ?=
export TRACELENS_MAKE ?= $(MAKE)

.PHONY: up-tracelens
up-tracelens:
	@set -eu; \
	case "$$TRACELENS_HOME" in /*) ;; *) echo 'TRACELENS_HOME must be an absolute path.' >&2; exit 1;; esac; \
	if [ -z "$$TRACELENS_PYTHON" ]; then \
		for candidate in python3 python3.14 python3.13 python3.12 python3.11 python3.10; do \
			if command -v "$$candidate" >/dev/null 2>&1 && "$$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null; then \
				TRACELENS_PYTHON="$$candidate"; export TRACELENS_PYTHON; break; \
			fi; \
		done; \
	fi; \
	command -v "$$TRACELENS_PYTHON" >/dev/null 2>&1 && "$$TRACELENS_PYTHON" -c 'import sys; sys.exit(sys.version_info < (3, 10))' || { echo 'Python 3.10+ is required; set TRACELENS_PYTHON if necessary.' >&2; exit 1; }; \
	if [ ! -e "$$TRACELENS_HOME" ]; then \
		echo 'Cloning TraceLens (requires access to the private repository)...'; \
		git clone -- "$$TRACELENS_REPO" "$$TRACELENS_HOME" || { \
			echo 'Cannot clone TraceLens. Ask an org admin to publish/grant access to chargebee-tech/cb-tracelens, or set TRACELENS_HOME to an existing checkout.' >&2; exit 1; }; \
	fi; \
	test -f "$$TRACELENS_HOME/scripts/bootstrap_cb_app.py" || { \
		echo 'TraceLens checkout is incomplete or too old: scripts/bootstrap_cb_app.py is missing. Update it explicitly; existing files were not replaced.' >&2; exit 1; }; \
	exec "$$TRACELENS_PYTHON" "$$TRACELENS_HOME/scripts/bootstrap_cb_app.py" --app-root "$(CURDIR)"
