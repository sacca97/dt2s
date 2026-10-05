.PHONY: build install build-release install-release adb-install release

APK := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release.apk

build:
	./gradlew assembleDebug

build-release:
	./gradlew assembleRelease

install: build
	@$(MAKE) --no-print-directory adb-install APK=$(APK)

install-release: build-release
	@$(MAKE) --no-print-directory adb-install APK=$(RELEASE_APK)

adb-install:
	@set -eu; \
	set -- $$(adb devices | awk 'NR > 1 && $$2 == "device" { print $$1 }'); \
	case "$$#" in \
		0) echo "No usable ADB devices found. Check 'adb devices'." >&2; exit 1 ;; \
		1) serial="$$1" ;; \
		*) \
			echo "Select an ADB device:"; \
			n=1; for device in "$$@"; do printf '  %s) %s\n' "$$n" "$$device"; n=$$((n + 1)); done; \
			count=$$#; \
			printf 'Device number [1-%s]: ' "$$count"; \
			read choice; \
			case "$$choice" in ''|*[!0-9]*) echo "Invalid selection." >&2; exit 1 ;; esac; \
			if [ "$$choice" -lt 1 ] || [ "$$choice" -gt "$$count" ]; then echo "Invalid selection." >&2; exit 1; fi; \
			serial=; n=1; \
			for device in "$$@"; do if [ "$$n" -eq "$$choice" ]; then serial="$$device"; break; fi; n=$$((n + 1)); done ;; \
	esac; \
	adb -s "$$serial" install -r "$(APK)"

# Usage: make release VERSION=1.1.0
release:
	@set -eu; \
	printf '%s\n' "$(VERSION)" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || { echo "Usage: make release VERSION=x.y.z" >&2; exit 1; }; \
	[ -z "$$(git status --porcelain)" ] || { echo "Working tree not clean." >&2; exit 1; }; \
	cur=$$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts); \
	if [ "$$cur" != "$(VERSION)" ]; then \
		code=$$(sed -n 's/.*versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts); \
		sed -i "s/versionCode = $$code/versionCode = $$((code + 1))/; s/versionName = \".*\"/versionName = \"$(VERSION)\"/" app/build.gradle.kts; \
		git commit -am "Release $(VERSION)"; \
	fi; \
	git tag "$(VERSION)"; \
	git push origin HEAD "$(VERSION)"
