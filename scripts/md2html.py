#!/usr/bin/env python3
"""把 docs/使用指南.md 转成可以直接分发的单文件 HTML（只用标准库）。

用法：python3 scripts/md2html.py docs/使用指南.md ~/Desktop/PocketAgent-UserGuide.html

支持的 Markdown 子集正好覆盖本仓库文档：标题、段落、引用、有序/无序列表、
管道表格、围栏代码块，以及行内的 `code`、**粗体**、[链接](url)。
"""

import html
import re
import sys

CSS = """
:root { color-scheme: light dark; }
body { max-width: 860px; margin: 0 auto; padding: 28px 20px 80px; font: 16px/1.7 -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
h1, h2, h3 { line-height: 1.3; margin-top: 1.6em; }
h1 { border-bottom: 2px solid #8884; padding-bottom: .3em; }
h2 { border-bottom: 1px solid #8883; padding-bottom: .2em; }
code { background: #8882; padding: .1em .35em; border-radius: 4px; font-size: .92em; }
pre { background: #8881; padding: 14px 16px; border-radius: 10px; overflow-x: auto; }
pre code { background: none; padding: 0; }
table { border-collapse: collapse; width: 100%; margin: 1em 0; }
th, td { border: 1px solid #8885; padding: 8px 10px; text-align: left; }
th { background: #8882; }
blockquote { border-left: 4px solid #8886; margin: 1em 0; padding: .2em 1em; color: #777; }
a { color: #2f6fdd; }
"""


def render_inline(text):
    out = html.escape(text, quote=False)
    spans = []

    def stash(match):
        spans.append(match.group(1))
        return "\x00%d\x00" % (len(spans) - 1)

    out = re.sub(r"`([^`]+)`", stash, out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', out)

    def unstash(match):
        return "<code>" + spans[int(match.group(1))] + "</code>"

    return re.sub(r"\x00(\d+)\x00", unstash, out)


def split_row(line):
    cells = line.strip().strip("|").split("|")
    return [c.strip() for c in cells]


def is_separator(line):
    return bool(re.match(r"^\s*\|?[\s:|-]+\|?\s*$", line)) and "-" in line


def convert(markdown_text):
    lines = markdown_text.splitlines()
    out = []
    i = 0
    paragraph = []
    list_items = []
    list_ordered = False
    quote = []

    def flush_paragraph():
        if paragraph:
            out.append("<p>" + render_inline(" ".join(paragraph)) + "</p>")
            paragraph.clear()

    def flush_list():
        nonlocal list_ordered
        if list_items:
            tag = "ol" if list_ordered else "ul"
            out.append("<%s>" % tag)
            for item in list_items:
                out.append("<li>" + render_inline(item) + "</li>")
            out.append("</%s>" % tag)
            list_items.clear()

    def flush_quote():
        if quote:
            out.append("<blockquote>" + render_inline(" ".join(quote)) + "</blockquote>")
            quote.clear()

    def flush_all():
        flush_paragraph()
        flush_list()
        flush_quote()

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            flush_all()
            i += 1
            code = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i])
                i += 1
            i += 1
            out.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
            continue

        if not stripped:
            flush_all()
            i += 1
            continue

        heading = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if heading:
            flush_all()
            level = len(heading.group(1))
            out.append("<h%d>%s</h%d>" % (level, render_inline(heading.group(2)), level))
            i += 1
            continue

        if stripped.startswith("|") and i + 1 < len(lines) and is_separator(lines[i + 1]):
            flush_all()
            header = split_row(stripped)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            out.append("<table>")
            out.append("<thead><tr>" + "".join("<th>%s</th>" % render_inline(c) for c in header) + "</tr></thead>")
            out.append("<tbody>")
            for row in rows:
                out.append("<tr>" + "".join("<td>%s</td>" % render_inline(c) for c in row) + "</tr>")
            out.append("</tbody></table>")
            continue

        if stripped.startswith("> "):
            flush_paragraph()
            flush_list()
            quote.append(stripped[2:].strip())
            i += 1
            continue

        if re.match(r"^[-*]\s+", stripped) or re.match(r"^\d+\.\s+", stripped):
            flush_paragraph()
            flush_quote()
            ordered = bool(re.match(r"^\d+\.\s+", stripped))
            if list_items and ordered != list_ordered:
                flush_list()
            list_ordered = ordered
            list_items.append(re.sub(r"^([-*]|\d+\.)\s+", "", stripped))
            i += 1
            # 续行（缩进）并进当前条目
            while i < len(lines) and lines[i].startswith("  ") and lines[i].strip():
                list_items[-1] += " " + lines[i].strip()
                i += 1
            continue

        flush_list()
        flush_quote()
        paragraph.append(stripped)
        i += 1

    flush_all()
    return "\n".join(out)


def main():
    if len(sys.argv) != 3:
        print("用法：python3 scripts/md2html.py <input.md> <output.html>", file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding="utf-8") as handle:
        body = convert(handle.read())
    document = (
        "<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        "<title>PocketAgent 使用指南</title>\n<style>" + CSS + "</style></head><body>\n"
        + body + "\n</body></html>\n"
    )
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(document)
    return 0


if __name__ == "__main__":
    sys.exit(main())
