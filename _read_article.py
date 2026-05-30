import re, sys
sys.stdout.reconfigure(encoding="utf-8")
with open(r"D:\Users\34647\Desktop\Android11 分享到微信时提示获取资源失败_mob64ca12d68df5的技术博客_51CTO博客.html", "r", encoding="utf-8", errors="ignore") as f:
    html = f.read()

text = re.sub(r"<script[^>]*>.*?</script>", " ", html, flags=re.DOTALL)
text = re.sub(r"<style[^>]*>.*?</style>", " ", text, flags=re.DOTALL)
text = re.sub(r"<br\s*/?>", "\n", text)
text = re.sub(r"</(?:p|div|li|h[1-6]|tr|blockquote|pre)>", "\n", text)
text = re.sub(r"<[^>]+>", " ", text)
for old, new in [("  ", " "), ("\t", " "), ("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&amp;", "&")]:
    text = text.replace(old, new)
lines = []
for line in text.split("\n"):
    line = line.strip()
    if len(line) > 5:
        lines.append(line)
for line in lines:
    print(line)