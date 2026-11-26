#!/usr/bin/env python3
"""Exercise the P2S HTML mockups in isolated, offline Chromium contexts.

Usage: python check_mockups.py PATH/TO/docs/mockups --out REPORT_DIR
Supply --viewport WIDTHxHEIGHT repeatedly to override the default browser sizes.
The HTML scenarios simulate state changes; passing here does not exercise Minecraft.
"""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

from playwright.sync_api import Page, sync_playwright


def viewport(value: str) -> tuple[int, int]:
    match = re.fullmatch(r"([1-9][0-9]*)x([1-9][0-9]*)", value)
    if not match:
        raise argparse.ArgumentTypeError("use WIDTHxHEIGHT, for example 1320x800")
    return int(match[1]), int(match[2])


def click(page: Page, selector: str) -> None:
    page.locator(selector).filter(visible=True).first.click()


class Scenario:
    """Collect independent failures while retaining their page, viewport and screenshot."""

    def __init__(self, page: Page, name: str, size: tuple[int, int], output: Path):
        self.page = page
        self.name = name
        self.size = size
        self.output = output
        self.checks = []
        self.page_errors = []
        self.failed_requests = []
        self.themes = []
        self.links = []
        self.screenshots = []

    def screenshot(self, label: str) -> None:
        base = self.name.removesuffix(".html").replace("/", "-")
        relative = f"screenshots/{base}-{self.size[0]}x{self.size[1]}-{label}.png"
        self.page.screenshot(path=str(self.output / relative), animations="disabled")
        self.screenshots.append(relative)

    def expect(self, label, actual, expected) -> None:
        passed = actual == expected
        self.checks.append(dict(check=label, passed=passed, actual=actual, expected=expected))
        if not passed:
            self.screenshot(f"failure-{len(self.checks)}")

    def run(self, label, action) -> None:
        try:
            action()
        except Exception as error:
            self.expect(label, str(error).split("Call log:")[0].strip(), "action completed")

    def check_links(self) -> None:
        for href in self.page.locator("a[href]").evaluate_all("nodes => nodes.map(e => e.href)"):
            url = urlsplit(href)
            if url.scheme == "file":
                self.links.append(href)
                self.expect("local link " + Path(url.path).name, Path(unquote(url.path)).is_file(), True)

    def claude_states(self, theme: str) -> None:
        for control in self.page.locator("button[data-state]").all():
            control.click()
            state = control.get_attribute("data-state")
            self.expect(f"selected state {state}", "on" in control.get_attribute("class").split(), True)
            if self.name.endswith("04-files.html"):
                for selector, visible in [(".code.only-view", state != "edit"), (".code.only-edit", state == "edit")]:
                    self.expect(f"{state} {selector}", self.page.locator(selector).is_visible(), visible)
                if theme == "contrast" and state == "edit":
                    panel = self.page.locator(".panel").bounding_box()
                    save = self.page.locator(".only-edit .btn").filter(has_text="Ctrl+S").bounding_box()
                    overflow = max(0, round(save["x"] + save["width"] - panel["x"] - panel["width"]))
                    self.expect("save button overflow beyond panel (CSS px)", overflow, 0)
            elif self.name.endswith("06-hud.html"):
                self.expect("HUD completion visible", self.page.locator(".only-done").first.is_visible(), state == "done")

    def check_themes(self, project: str) -> None:
        page = self.page
        if project == "gpt":
            count = page.locator("#themeData").evaluate("node => JSON.parse(node.textContent).themes.length")
            for _ in range(count):
                click(page, '[data-act="theme"]')
                self.themes.append(page.locator("html").get_attribute("style"))
        else:
            selectors = {
                "claude": ("button[data-theme]", "data-theme", ".stage"),
                "gemini": ("button[data-theme-btn]", "data-theme-btn", "html"),
                "gemini-xhigh": ("button[data-theme-id]", "data-theme-id", "html"),
            }
            if project not in selectors:
                return
            selector, attribute, target = selectors[project]
            buttons = page.locator(selector)
            count = buttons.count()
            if count == 0 and self.name == "claude/index.html":
                return  # Static launcher without theme controls.
            self.expect("has theme controls", count > 0, True)
            for control in buttons.all():
                control.click()
                self.themes.append(page.locator(target).get_attribute("style"))
                if project == "claude":
                    self.claude_states(control.get_attribute(attribute))
        self.expect("distinct applied CSS palettes", len({s for s in self.themes if s}), count)

    def check_actions(self, project, file: Path) -> None:
        page = self.page
        if project == "claude":
            if page.locator("#showjson").count():
                click(page, "#showjson")
                self.expect("theme JSON open", page.locator("#json").is_visible(), True)
                click(page, "#showjson")
            return
        if project == "gemini":
            if file.name.startswith("01-"):
                page.locator("#chatInput").fill("queue acceptance")
                click(page, '[onclick="enqueueMsg()"]')
                self.expect("queued input visible", "queue acceptance" in page.locator("#queuePreview").inner_text(), True)
                click(page, '[onclick="withdrawQueue()"]')
                self.expect("queue withdrawn", page.locator("#queueStrip").is_visible(), False)
            elif file.name.startswith("02-"):
                click(page, '[onclick^="quickApply"]')
                self.expect("apply one", page.locator("#tabPendingBadge").inner_text(), "2")
                click(page, '[onclick="discardAllDemo()"]')
                self.expect("discard remaining", page.locator("#tabPendingBadge").inner_text(), "0")
            elif file.name.startswith("03-"):
                click(page, '[onclick="toggleDiscardBar(true)"]')
                page.locator("#discardReasonInput").fill("fixture reason")
                click(page, '[onclick="confirmDiscard()"]')
                self.expect("discard one", page.locator("#tabBadge").inner_text(), "2")
            elif file.name.startswith("04-"):
                click(page, '[onclick*="openDoc(\'roof\')"]')
                self.expect("file selected", "roof" in page.locator("#editorFileTitle").inner_text(), True)
            elif file.name.startswith("05-"):
                click(page, '[onclick*="switchNarrowTab(\'files\')"]')
                click(page, '[onclick*="showNarrowEditor(\'roof.toml\')"]')
                self.expect("narrow editor return button", page.locator('[onclick="showNarrowList()"]').is_visible(), True)
            return
        if project == "gemini-xhigh":
            if file.name.startswith("01-"):
                click(page, "#clearQueueBtn")
                self.expect("queue cleared", page.locator("#queueStrip").is_visible(), False)
            elif file.name.startswith("03-"):
                click(page, "#discardTriggerBtn")
                page.locator("#discardReasonInput").fill("fixture reason")
                click(page, "#confirmDiscardBtn")
                self.expect("discard one", page.locator("#tabBadge").inner_text(), "2")
                click(page, "#applyCurrentBtn")
                self.expect("apply after discard", page.locator("#tabBadge").inner_text(), "1")
            elif file.name.startswith("05-"):
                click(page, '[data-nview="files"]')
                self.expect("narrow files selected", "active" in page.locator('[data-nview="files"]').get_attribute("class").split(), True)
            return
        if project == "gpt":
            if file.stem in ("index", "running", "pending", "narrow"):
                mode = page.locator("#worldmode")
                before = mode.get_attribute("aria-pressed")
                mode.click()
                self.expect("world preview toggled", mode.get_attribute("aria-pressed") != before, True)
            elif file.stem == "review":
                click(page, '[data-act="discard"]')
                self.expect("confirm discard open", page.locator("#confirm").is_visible(), True)
                click(page, "#cancel")
                self.expect("cancel keeps review", page.locator('[data-act="discard"]').is_visible(), True)
                click(page, '[data-act="apply"]')
                self.expect("one applied", page.evaluate("state.statuses.filter(s => s === 'applied').length"), 1)
            elif file.stem == "files":
                click(page, '[data-act="edit"]')
                page.locator("#editor").fill("fixture.toml")
                click(page, '[data-act="save"]')
                self.expect("edit stages file", page.evaluate("state.tab === 'review' && state.statuses[state.selected] === 'pending'"), True)
            if file.stem == "index":
                value = json.loads((file.parent / "themes.json").read_text())
                value["themes"][0]["name"] = "Imported test palette"
                value["default"] = value["themes"][0]["id"]
                theme_file = page.locator("#themeFile")
                theme_file.set_input_files({"name": "palette.json", "mimeType": "application/json", "buffer": json.dumps(value).encode()})
                page.locator("#themeName").get_by_text("Imported test palette", exact=True).wait_for()
                theme_file.set_input_files({"name": "broken.json", "mimeType": "application/json", "buffer": b"{}"})
                page.locator("#importMessage").get_by_text("读取失败", exact=False).wait_for()
                self.expect("invalid import retains working palette", page.locator("#themeName").inner_text(), "Imported test palette")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mockups", type=Path, help="directory containing the HTML mockup suites")
    parser.add_argument("--out", type=Path, required=True, help="report and screenshots directory outside the mockups")
    parser.add_argument("--viewport", type=viewport, action="append", help="browser viewport, repeatable")
    args = parser.parse_args()
    source, output = args.mockups.resolve(), args.out.resolve()
    files = sorted(source.rglob("*.html"))
    if not files or output.is_relative_to(source):
        parser.error("supply a populated mockups directory and a separate output directory")
    sizes = args.viewport or [(1320, 800), (960, 540), (640, 360), (320, 240)]
    (output / "screenshots").mkdir(parents=True, exist_ok=True)
    rows = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        version = browser.version
        for size in sizes:
            for file in files:
                context = browser.new_context(viewport=dict(zip(("width", "height"), size)), offline=True,
                                              service_workers="block", accept_downloads=False, device_scale_factor=1)
                page = context.new_page()
                page.set_default_timeout(2500)
                page.on("dialog", lambda dialog: dialog.accept())
                case = Scenario(page, str(file.relative_to(source)), size, output)
                page.on("pageerror", lambda error: case.page_errors.append(str(error)))
                page.on("requestfailed", lambda request: case.failed_requests.append(request.url))
                project = file.parent.name
                case.run("load local page", lambda: page.goto(file.as_uri(), wait_until="load"))
                case.run("visible body", lambda: case.expect("body text", bool(page.locator("body").inner_text().strip()), True))
                if size == sizes[0]:
                    case.run("local links", case.check_links)
                    case.run("initial screenshot", lambda: case.screenshot("initial"))
                case.run("theme and state controls", lambda: case.check_themes(project))
                if size == sizes[0]:
                    case.run("scene interaction", lambda: case.check_actions(project, file))
                # These are often fixed-scale presentation sheets. Record scroll separately from clipped in-game controls.
                sheet_width = page.evaluate("document.documentElement.scrollWidth")
                rows.append(dict(page=case.name, viewport=size, sheet_width=sheet_width, links=case.links,
                                 theme_states=len(case.themes), checks=case.checks, screenshots=case.screenshots,
                                 page_errors=case.page_errors, failed_requests=case.failed_requests))
                context.close()
        browser.close()
    failures = sum(sum(not c["passed"] for c in row["checks"]) + len(row["page_errors"]) + len(row["failed_requests"]) for row in rows)
    summary = dict(browser=version, pages=len(files), samples=len(rows), links=sum(len(r["links"]) for r in rows),
                   theme_states=sum(r["theme_states"] for r in rows), failures=failures)
    (output / "report.json").write_text(json.dumps(dict(summary=summary, samples=rows), ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
