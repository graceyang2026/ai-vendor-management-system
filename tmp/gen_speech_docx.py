# -*- coding: utf-8 -*-
"""零依赖：把 tmp/speech_module12.md 转成带真实 Heading 样式的 .docx（复用验收稿的 OOXML 逻辑）"""
import re, zipfile

MD = 'tmp/speech_module12.md'
OUT = 'docs/AI辅助开发汇报演讲稿-供应商与状态机-20261016.docx'
W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


TOKEN = re.compile(r'\*\*(.+?)\*\*|`([^`]+)`')


def make_runs(s):
    runs, pos = [], 0
    for m in TOKEN.finditer(s):
        if m.start() > pos:
            runs.append((s[pos:m.start()], {}))
        if m.group(1) is not None:
            runs.append((m.group(1), {'bold': True}))
        else:
            runs.append((m.group(2), {'mono': True}))
        pos = m.end()
    if pos < len(s):
        runs.append((s[pos:], {}))
    if not runs:
        runs = [('', {})]
    return runs


def run_xml(text, opts):
    rpr = ''
    props = []
    if opts.get('bold'):
        props.append('<w:b/>')
    if opts.get('mono'):
        props.append('<w:rFonts w:ascii="Menlo" w:hAnsi="Menlo" w:cs="Menlo"/>')
        props.append('<w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/>')
    if props:
        rpr = '<w:rPr>' + ''.join(props) + '</w:rPr>'
    return f'<w:r>{rpr}<w:t xml:space="preserve">{esc(text)}</w:t></w:r>'


def para(raw, style=None, ind_left=None, first=None):
    ppr = []
    if style:
        ppr.append(f'<w:pStyle w:val="{style}"/>')
    if ind_left is not None:
        fl = f' w:firstLine="{first}"' if first is not None else ''
        ppr.append(f'<w:ind w:left="{ind_left}"{fl}/>')
    p = f'<w:p><w:pPr>{"".join(ppr)}</w:pPr>' if ppr else '<w:p>'
    body = ''.join(run_xml(t, o) for t, o in make_runs(raw))
    return p + body + '</w:p>'


def hr():
    return ('<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" '
            'w:space="1" w:color="BFBFBF"/></w:pBdr></w:pPr></w:p>')


lines = open(MD, encoding='utf-8').read().splitlines()
paras = []
i, n = 0, len(lines)
while i < n:
    line = lines[i]
    s = line.strip()
    if s == '':
        i += 1
        continue
    if re.fullmatch(r'-{3,}', s):
        paras.append(hr())
        i += 1
        continue
    m = re.match(r'^(#{1,6})\s+(.*)$', line)
    if m:
        lvl = min(len(m.group(1)), 4)
        paras.append(para(m.group(2), style=f'Heading{lvl}'))
        i += 1
        continue
    if s.startswith('>'):
        content = re.sub(r'^\s*>\s?', '', line)
        paras.append(para(content if content.strip() else ' ', style='Quote'))
        i += 1
        continue
    m = re.match(r'^\s*-\s+(.*)$', line)
    if m:
        paras.append(para('•  ' + m.group(1), ind_left='360', first='-220'))
        i += 1
        continue
    paras.append(para(s))
    i += 1

body = ''.join(paras)
document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<w:document {W}><w:body>{body}'
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" '
            'w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>')


def hstyle(sid, name, lvl, sz, before, after):
    return (f'<w:style w:type="paragraph" w:styleId="{sid}">'
            f'<w:name w:val="{name}"/><w:basedOn w:val="Normal"/>'
            f'<w:pPr><w:keepNext/><w:outlineLvl w:val="{lvl}"/>'
            f'<w:spacing w:before="{before}" w:after="{after}"/></w:pPr>'
            f'<w:rPr><w:b/><w:sz w:val="{sz}"/><w:color w:val="1F3864"/></w:rPr></w:style>')


styles = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          f'<w:styles {W}>'
          '<w:docDefaults><w:rPrDefault><w:rPr>'
          '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="PingFang SC"/>'
          '<w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>'
          '<w:style w:type="paragraph" w:default="1" w:styleId="Normal">'
          '<w:name w:val="Normal"/><w:pPr><w:spacing w:after="80"/></w:pPr></w:style>'
          + hstyle('Heading1', 'heading 1', 0, 36, 240, 120)
          + hstyle('Heading2', 'heading 2', 1, 30, 200, 100)
          + hstyle('Heading3', 'heading 3', 2, 26, 160, 80)
          + hstyle('Heading4', 'heading 4', 3, 24, 140, 60)
          + '<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/>'
            '<w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="360"/>'
            '<w:pBdr><w:left w:val="single" w:sz="12" w:space="8" w:color="BFBFBF"/></w:pBdr></w:pPr>'
            '<w:rPr><w:i/><w:color w:val="595959"/></w:rPr></w:style>'
          '</w:styles>')

content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                 '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                 '<Default Extension="xml" ContentType="application/xml"/>'
                 '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                 '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
                 '</Types>')

rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        '</Relationships>')

doc_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            '</Relationships>')

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml', content_types)
    z.writestr('_rels/.rels', rels)
    z.writestr('word/document.xml', document)
    z.writestr('word/_rels/document.xml.rels', doc_rels)
    z.writestr('word/styles.xml', styles)

print('DOCX 生成完成:', OUT, '| 段落块:', len(paras))
