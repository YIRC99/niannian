param([Parameter(Mandatory=$true)][string]$RunDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runRoot = (Resolve-Path -LiteralPath $RunDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'UI设计/manifest.json') -Raw | ConvertFrom-Json
$runUri = [Uri]::new($runRoot.TrimEnd('\','/') + '/')
function Html([string]$value) { [System.Net.WebUtility]::HtmlEncode($value) }
function Relative([string]$path) { $runUri.MakeRelativeUri([Uri]::new($path)).ToString() }
$sections = [System.Collections.Generic.List[string]]::new()
$records = [System.Collections.Generic.List[object]]::new()
foreach ($board in $manifest.boards) {
    $cards = [System.Collections.Generic.List[string]]::new()
    foreach ($page in $board.pages) {
        $shot = @(Get-ChildItem -LiteralPath $runRoot -Recurse -File -Filter ($page.id + '.png'))
        if ($shot.Count -ne 1) { throw "页面 $($page.id) 的截图数量应为 1，实际为 $($shot.Count)" }
        $bottom = Join-Path $shot[0].DirectoryName ($page.id + '-bottom.png')
        $src = Relative $shot[0].FullName
        $bottomLink = if (Test-Path -LiteralPath $bottom) { '<a href="' + (Html (Relative $bottom)) + '" target="_blank">查看长页底部</a>' } else { '' }
        $title = Html ($page.id + ' · ' + $page.title)
        $cards.Add('<article><h3>' + $title + '</h3><a href="' + (Html $src) + '" target="_blank"><img loading="lazy" src="' + (Html $src) + '" alt="' + $title + '"></a><footer><a href="' + (Html $src) + '" target="_blank">打开原图</a>' + $bottomLink + '</footer></article>')
        $records.Add([ordered]@{ id=$page.id; title=$page.title; board=$board.id; screenshot=$src; bottom=if(Test-Path -LiteralPath $bottom){Relative $bottom}else{$null}; sha256=(Get-FileHash -LiteralPath $shot[0].FullName -Algorithm SHA256).Hash.ToLower() })
    }
    $design = Relative (Join-Path $projectRoot ('UI设计/' + $board.image))
    $sections.Add('<section data-search="' + (Html ($board.title + ' ' + ($board.pages.id -join ' '))) + '"><h2>' + (Html $board.title) + '</h2><details><summary>打开对应设计板</summary><a href="' + (Html $design) + '" target="_blank"><img class="board" loading="lazy" src="' + (Html $design) + '" alt="' + (Html $board.title) + '"></a></details><div class="grid">' + ($cards -join '') + '</div></section>')
}
$html = @'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>念念 · 全页面截图对照</title>
<style>*{box-sizing:border-box}body{margin:0;background:#faf8f0;color:#203e2d;font:16px/1.6 system-ui,"Microsoft YaHei",sans-serif}header,main{max-width:1250px;margin:auto;padding:24px}h1{font-size:32px;margin:0}p{max-width:950px;color:#56665b}input{font:inherit;padding:10px 14px;border:1px solid #cad7c4;border-radius:9px;width:min(100%,420px);background:#fffef9}section{padding:16px 0 44px;border-bottom:1px solid #d7dfd0}h2{font-size:25px}summary{cursor:pointer;padding:8px 0;color:#4f7451}.board{display:block;width:100%;margin:12px 0}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}article{min-width:0}h3{font-size:16px;font-weight:600}article img{display:block;width:100%;border-radius:12px;border:1px solid #dce3d6}footer{display:flex;gap:16px;padding:12px 0;flex-wrap:wrap}a{color:#426a49;text-underline-offset:4px}a:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid #a18440;outline-offset:4px}@media(max-width:700px){.grid{grid-template-columns:1fr}header,main{padding:18px}}</style>
<header><h1>念念 · 全页面截图对照</h1><p>72 个页面与状态，24 张设计板。截图来自独立测试包和本地样例数据，长页附底部截图。系统选图、相机、分享界面随设备变化；本页不代表真实后端邮件、AI 或实际文件分享已验收。</p><label>按页面编号或模块查找<br><input id="search" type="search" placeholder="例如：52、花园、账号"></label></header><main>
'@
$html += $sections -join "`n"
$html += '</main><script>document.querySelector("#search").addEventListener("input",e=>{const q=e.target.value.trim();document.querySelectorAll("section").forEach(s=>s.hidden=q&&!s.dataset.search.includes(q));});</script></html>'
$html | Set-Content -LiteralPath (Join-Path $runRoot 'index.html') -Encoding utf8
[ordered]@{generated_at=(Get-Date -Format o);pages=$records} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runRoot 'screenshots.json') -Encoding utf8
Write-Output "对照页：$(Join-Path $runRoot 'index.html')，共 $($records.Count) 个状态。"
