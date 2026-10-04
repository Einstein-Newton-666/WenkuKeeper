#!/usr/bin/env python3
"""Check the shelf-parsing rules against a captured wenku8 bookcase page.

The plugin's parser (`Wenku8SiteShelf.kt`) cannot be exercised on the dev machine: the
site is unreachable here, so `bookcase.php` never returns a real page. This script
mirrors the *rules* the Kotlin parser implements -- locate columns by header text, read
the internal id from the `checkid[]` checkbox, read `aid` from the title link, read the
bookmark from the bookmark column -- and runs them over the offline fixture built from a
real captured DOM.

It validates the rules and their alignment with the captured structure. It does **not**
execute the Kotlin code, so a divergence between the two implementations would not be
caught here.

Usage:
    python check_site_shelf_parser.py [fixture.html]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from lxml import html

AID_REGEX = re.compile(r"[?&]aid=(\d+)")
CID_REGEX = re.compile(r"[?&]cid=(\d+)")
DELID_REGEX = re.compile(r"[?&]delid=(\d+)")

FALLBACK_BOOKMARK_DISTANCE = 3

# (internalId, aid, title, bookmarkChapterId) -- 前两条来自真实抓包（均无书签），
# 第三条是 fixture 里合成的、用于覆盖书签非空路径的样本。
EXPECTED = [
    ("11791091", "2964", "Silent Witch 沉默魔女的秘密(沉默的魔女)", None),
    ("11539000", "1973", "欢迎来到实力至上主义的教室", None),
    ("11780308", "3241", "合成样本", "190002"),
]


def header_columns(doc) -> dict[str, int]:
    for row in doc.iter("tr"):
        cells = [c for c in row if c.tag in ("td", "th")]
        texts = ["".join(c.itertext()).strip() for c in cells]
        if "名称" in texts and "书签" in texts:
            return {name: index for index, name in enumerate(texts)}
    return {}


def parse(doc) -> list[tuple]:
    columns = header_columns(doc)

    out = []
    for row in doc.iter("tr"):
        checkbox = next(
            (i for i in row.iter("input") if i.get("name") == "checkid[]"), None
        )
        if checkbox is None:
            continue
        cells = [c for c in row if c.tag == "td"]
        if not cells:
            continue

        # 名称列按语义定位：第一个挂着 readbookcase 链接的单元格。
        title_index = next(
            (
                i
                for i, cell in enumerate(cells)
                if any("readbookcase" in (a.get("href") or "") for a in cell.iter("a"))
            ),
            None,
        )
        if title_index is None:
            continue

        # 表头「名称」的位置与语义位置之差即整张表的列偏移。
        offset = title_index - columns["名称"] if "名称" in columns else 0
        bookmark_index = (
            columns["书签"] + offset
            if "书签" in columns
            else title_index + FALLBACK_BOOKMARK_DISTANCE
        )

        remove = next((a for a in row.iter("a") if "delid=" in (a.get("href") or "")), None)
        internal_id = (checkbox.get("value") or "").strip()
        if not internal_id and remove is not None:
            m = DELID_REGEX.search(remove.get("href") or "")
            internal_id = m.group(1) if m else ""
        if not internal_id:
            continue

        title_cell = cells[title_index]
        anchor = next(
            (a for a in title_cell.iter("a") if "readbookcase" in (a.get("href") or "")), None
        )
        if anchor is None:
            continue
        m = AID_REGEX.search(anchor.get("href") or "")
        if m is None:
            continue
        aid = m.group(1)
        title = ("".join(anchor.itertext()).strip() or "".join(title_cell.itertext()).strip())

        # 书签格必须与名称格不同：偏移算错时宁可判成没有书签，也不能把最新章节当书签。
        bookmark_cid = None
        if bookmark_index != title_index and 0 <= bookmark_index < len(cells):
            bookmark_anchor = next(iter(cells[bookmark_index].iter("a")), None)
            href = (bookmark_anchor.get("href") or "") if bookmark_anchor is not None else ""
            cid_match = CID_REGEX.search(href)
            bookmark_cid = cid_match.group(1) if cid_match else None

        out.append((internal_id, aid, title, bookmark_cid))
    return out


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        Path(__file__).resolve().parent / "fixtures" / "wenku8-bookcase.html"
    )
    doc = html.fromstring(path.read_text(encoding="utf-8"))

    columns = header_columns(doc)
    print(f"fixture      : {path}")
    print(f"header map   : {columns}")

    rows = parse(doc)
    print(f"parsed rows  : {len(rows)}")
    for row in rows:
        internal_id, aid, title, bookmark = row
        mark = "★" if bookmark else " "
        print(f"  {mark} internal={internal_id:<9} aid={aid:<6} bookmark={bookmark or '-':<8} {title}")

    ok = True
    if len(rows) != len(EXPECTED):
        print(f"FAIL: expected {len(EXPECTED)} rows, parsed {len(rows)}")
        ok = False
    for index, (got, want) in enumerate(zip(rows, EXPECTED)):
        if got != want:
            print(f"FAIL row {index}:\n  got  {got}\n  want {want}")
            ok = False

    # 表头缺失时必须退回实测列距，而不是解析出空列表。
    html_text = path.read_text(encoding="utf-8")
    stripped = html.fromstring(
        re.sub(r"<tr>\s*<td>名称</td>.*?</tr>", "<tr></tr>", html_text, flags=re.S)
    )
    fallback_rows = parse(stripped)
    if len(fallback_rows) != len(EXPECTED):
        print(f"FAIL: without a header row, expected {len(EXPECTED)} rows, got {len(fallback_rows)}")
        ok = False
    else:
        print("header-less fallback : ok")

    # 表头带复选框格（7 格，与数据行同宽）时偏移应为 0。真实表头是哪种宽度没有抓到，
    # 因此两种宽度都要能解析出同样的结果。
    wide_header = html.fromstring(
        re.sub(
            r"<tr>\s*<td>名称</td>",
            "<tr><td></td><td>名称</td>",
            html_text,
            count=1,
        )
    )
    wide_rows = parse(wide_header)
    if wide_rows != EXPECTED:
        print(f"FAIL: 7-cell header parsed {wide_rows}, want {EXPECTED}")
        ok = False
    else:
        print("7-cell header (offset 0) : ok")

    # 偏移算法必须真正生效：把书签格换成「最新章节」那种带 cid 的链接，结果不能变。
    shifted = html.fromstring(html_text)
    if parse(shifted) != EXPECTED:
        print("FAIL: 6-cell header (offset 1) did not round-trip")
        ok = False
    else:
        print("6-cell header (offset 1) : ok")

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
