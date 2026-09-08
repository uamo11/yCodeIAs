$repoRoot = "C:\Users\Usuario\Documents\ANDROID\yCodeIAs"
$pagesDir = Join-Path $repoRoot "pages"
$targetRepo = "https://github.com/uamo11/yCodeIAs.git"

$tempDir = Join-Path $env:TEMP ("ghpages_" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

Write-Host "Copying pages files to $tempDir..."
Get-ChildItem -Path $pagesDir | Copy-Item -Destination $tempDir -Force
Get-ChildItem -Path $tempDir | ForEach-Object { Write-Host " - $($_.Name) ($($_.Length) bytes)" }

Push-Location $tempDir
try {
    git init
    git checkout -b gh-pages
    git add -A
    git commit -m "Deploy complete yCode landing page and visual assets to GitHub Pages"
    git remote add origin $targetRepo
    git push -u origin gh-pages --force
    Write-Host "gh-pages branch successfully pushed!"
}
finally {
    Pop-Location
    Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
}

