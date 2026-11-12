import os,json,re,time,hashlib,zipfile,tempfile,base64,urllib.request,urllib.error
from datetime import datetime,timezone,timedelta
from pathlib import Path
REPO=os.environ["GITHUB_REPOSITORY"]
assert REPO=="DannyNov/HA-Custom-Widgets"
APPLY=os.environ.get("APPLY","false")=="true"
TOKEN=os.environ["GH_TOKEN"]
BASE="https://api.github.com/"
def api(path,method="GET"):
    req=urllib.request.Request(BASE+path,method=method,headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req,timeout=90) as r:
                data=r.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            if e.code==404 and method=="DELETE": return None
            if e.code in (429,500,502,503,504) and attempt<3: time.sleep(3*(attempt+1)); continue
            raise
def pages(path,key=None):
    out=[]
    for page in range(1,100):
        d=api(path+("&" if "?" in path else "?")+f"per_page=100&page={page}")
        batch=d[key] if key else d
        out+=batch
        if len(batch)<100:return out
    raise RuntimeError("pagination safety cap")
def download(url,dest,auth=False):
    # Do not forward credentials to signed storage redirects.
    headers={"Accept":"application/vnd.github+json" if "/actions/artifacts/" in url else "application/octet-stream"}
    if auth: headers["Authorization"]="Bearer "+TOKEN
    class Redirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,hdrs,newurl):
            r=super().redirect_request(req,fp,code,msg,hdrs,newurl)
            if r is not None:r.remove_header("Authorization")
            return r
    opener=urllib.request.build_opener(Redirect())
    with opener.open(urllib.request.Request(url,headers=headers),timeout=180) as r,open(dest,"wb") as f:
        while chunk:=r.read(1024*1024):f.write(chunk)
def digest(path):
    h=hashlib.sha256()
    with open(path,"rb") as f:
        while chunk:=f.read(1024*1024):h.update(chunk)
    return h.hexdigest()

if APPLY:
    out=Path("storage-audit");out.mkdir(exist_ok=True)
    approval=json.loads(Path(".github/storage-cleanup-approved.json").read_text())
    approved=set(approval["ids"]);protected=set(approval["protected_runs"])
    proofs={a["id"]:a for a in approval["verified_release_copies"]}
    runs=pages(f"repos/{REPO}/actions/runs","workflow_runs")
    runmap={r["id"]:r for r in runs}
    seen=set()
    for branch in pages(f"repos/{REPO}/branches"):
        tree=api(f"repos/{REPO}/git/trees/{branch['commit']['sha']}?recursive=1")
        assert not tree.get("truncated")
        for item in tree["tree"]:
            p=item["path"]
            if item["type"]!="blob" or not p.startswith(".github/") or item["sha"] in seen:continue
            if not p.endswith((".yml",".yaml",".py",".cjs",".js",".sh",".json")) or "storage-maintenance" in p or "storage-cleanup-approved" in p:continue
            seen.add(item["sha"])
            blob=api(f"repos/{REPO}/git/blobs/{item['sha']}")
            text=base64.b64decode(blob["content"]).decode("utf8")
            protected.update(int(n) for n in re.findall(r"(?<![0-9])[0-9]{11,12}(?![0-9])",text) if int(n) in runmap)
    arts=pages(f"repos/{REPO}/actions/artifacts","artifacts")
    releases=pages(f"repos/{REPO}/releases")
    bytag={r["tag_name"]:r for r in releases}
    before={"count":len(arts),"bytes":sum(a["size_in_bytes"] for a in arts)}
    deleted=[]
    for a in arts:
        if a["id"] not in approved:continue
        assert a["workflow_run"]["id"] not in protected
        proof=proofs.get(a["id"])
        if proof:
            assert a["name"]==proof["name"] and a["size_in_bytes"]==proof["bytes"] and a["workflow_run"]["head_sha"]==proof["sha"]
            for binary in proof["matches"]:
                release=bytag[binary["tag"]];assert not release["draft"]
                asset=next(x for x in release["assets"] if x["name"]==binary["name"])
                assert asset["digest"]=="sha256:"+binary["sha256"] and asset["size"]==binary["bytes"]
        else:
            assert re.fullmatch(r"v[0-9.]+-(?:test-results|glance-api-[0-9]+|upgrade-.+|rc[0-9]+-host-api-[0-9]+)",a["name"])
        api(f"repos/{REPO}/actions/artifacts/{a['id']}","DELETE")
        row={"id":a["id"],"name":a["name"],"bytes":a["size_in_bytes"],"run":a["workflow_run"]["id"]}
        deleted.append(row)
        (out/"deleted.json").write_text(json.dumps(deleted,indent=2))
        print("DELETED_APPROVED",json.dumps(row),flush=True)
    for attempt in range(5):
        after=pages(f"repos/{REPO}/actions/artifacts?verify={time.time_ns()}","artifacts")
        if not any(a["id"] in approved for a in after):break
        time.sleep(2)
    else:raise AssertionError("Approved artifact IDs remain after cleanup")
    assert all(a["id"] in {x["id"] for x in after} for a in arts if a["workflow_run"]["id"] in protected and not a["expired"])
    final={"before":before,"deleted_count":len(deleted),"deleted_bytes":sum(a["bytes"] for a in deleted),"remaining_count":len(after),"remaining_bytes":sum(a["size_in_bytes"] for a in after),"protected_runs":sorted(protected),"scanned_files":len(seen)}
    (out/"summary.json").write_text(json.dumps(final,indent=2))
    print("FINAL_APPROVED_SUMMARY",json.dumps(final),flush=True)
    raise SystemExit(0)

out=Path("storage-audit");out.mkdir(exist_ok=True)
runs=pages(f"repos/{REPO}/actions/runs","workflow_runs")
runmap={r["id"]:r for r in runs}
artifacts=pages(f"repos/{REPO}/actions/artifacts","artifacts")
releases=pages(f"repos/{REPO}/releases")
# Preserve all historical run references in GitHub workflow/scripts across every live branch.
protected={37691055760,37691055553,37693786312,37693786513}
seen=set();scan_count=0
branches=pages(f"repos/{REPO}/branches")
for b in branches:
    tree=api(f"repos/{REPO}/git/trees/{b['commit']['sha']}?recursive=1")
    assert not tree.get("truncated"),"Incomplete dependency scan"
    for entry in tree["tree"]:
        path=entry["path"]
        if entry["type"]!="blob" or not path.startswith(".github/") or entry["sha"] in seen:continue
        if not path.endswith((".yml",".yaml",".py",".cjs",".js",".sh",".json")):continue
        if "storage-maintenance" in path:continue
        seen.add(entry["sha"])
        blob=api(f"repos/{REPO}/git/blobs/{entry['sha']}")
        text=base64.b64decode(blob["content"]).decode("utf8")
        protected.update(int(n) for n in re.findall(r"(?<![0-9])[0-9]{11,12}(?![0-9])",text) if int(n) in runmap)
        scan_count+=1
# Latest successful and failed runs of every workflow retain diagnosis/baseline evidence.
for wid in {r["workflow_id"] for r in runs}:
    for result in ("success","failure"):
        matches=[r for r in runs if r["workflow_id"]==wid and r["conclusion"]==result]
        if matches:protected.add(max(matches,key=lambda r:r["created_at"])["id"])
# Verify release binaries by downloading their actual bytes, SHA256 and size.
verified={}
release_evidence=[]
with tempfile.TemporaryDirectory() as tmp:
    for release in releases:
        if release["draft"]:continue
        for asset in release["assets"]:
            if not asset["name"].lower().endswith((".apk",".aab")):continue
            expected=asset.get("digest","")
            if not expected or not expected.startswith("sha256:"):continue
            target=Path(tmp)/str(asset["id"])
            try:
                download(asset["browser_download_url"],target)
                actual=digest(target)
                assert target.stat().st_size==asset["size"]
                assert "sha256:"+actual==expected
                verified[actual]={"tag":release["tag_name"],"name":asset["name"],"url":asset["browser_download_url"],"bytes":asset["size"]}
                release_evidence.append(verified[actual]|{"sha256":actual,"target":release["target_commitish"]})
            except Exception as e:
                print("RELEASE_VERIFY_FAILED",asset["name"],type(e).__name__,flush=True)
            finally:
                target.unlink(missing_ok=True)
    plan=[];kept=[]
    cutoff=(datetime.now(timezone.utc)-timedelta(days=30)).isoformat().replace("+00:00","Z")
    successful={}
    for r in runs:
        if r["conclusion"]=="success":successful[r["workflow_id"]]=max(successful.get(r["workflow_id"],""),r["created_at"])
    for a in artifacts:
        rid=a["workflow_run"]["id"];run=runmap.get(rid)
        record={"id":a["id"],"name":a["name"],"bytes":a["size_in_bytes"],"run":rid,"sha":a["workflow_run"]["head_sha"],"created":a["created_at"]}
        if a["expired"] or rid in protected or not run or a["created_at"]>=cutoff:
            kept.append(record|{"reason":"protected-or-recent"});continue
        name=a["name"]
        # Old report-only artifacts; no APK/AAB/firmware names accepted.
        report=bool(re.fullmatch(r"v[0-9.]+-(?:test-results|glance-api-[0-9]+|upgrade-.+|rc[0-9]+-host-api-[0-9]+)",name))
        if report and successful.get(run["workflow_id"],"")>run["created_at"]:
            plan.append(record|{"reason":"obsolete-test-report-with-newer-success"});continue
        if "release" not in name.lower() and "public-verification" not in name.lower():
            kept.append(record|{"reason":"unique-or-unclassified"});continue
        target=Path(tmp)/"artifact.zip"
        try:
            download(BASE+f"repos/{REPO}/actions/artifacts/{a['id']}/zip",target,True)
            if a.get("digest"):assert "sha256:"+digest(target)==a["digest"]
            matches=[]
            with zipfile.ZipFile(target) as z:
                binaries=[f for f in z.infolist() if not f.is_dir() and f.filename.lower().endswith((".apk",".aab"))]
                assert binaries,"No release binaries"
                assert all(f.file_size<500*1024*1024 for f in binaries),"Oversize member"
                for f in binaries:
                    h=hashlib.sha256()
                    with z.open(f) as stream:
                        while chunk:=stream.read(1024*1024):h.update(chunk)
                    actual=h.hexdigest()
                    assert actual in verified,"Binary has no verified release copy"
                    assert f.file_size==verified[actual]["bytes"],"Binary size mismatch"
                    matches.append({"member":f.filename,"sha256":actual,**verified[actual]})
            plan.append(record|{"reason":"all-apk-aab-sha256-match-downloaded-releases","matches":matches})
        except Exception as e:
            kept.append(record|{"reason":"not-proven-duplicate","detail":str(e)[:150]})
        finally:target.unlink(missing_ok=True)
        if len(plan)%25==0:print("PROGRESS",len(plan),len(kept),flush=True)
    (out/"plan.json").write_text(json.dumps(plan,indent=2))
    (out/"kept.json").write_text(json.dumps(kept,indent=2))
    (out/"releases-verified.json").write_text(json.dumps(release_evidence,indent=2))
    (out/"protected-runs.json").write_text(json.dumps(sorted(protected)))
    print("KEPT_REASONS",json.dumps({reason:sum(a["reason"]==reason for a in kept) for reason in set(a["reason"] for a in kept)}),flush=True)
    print("PLAN_SUMMARY",json.dumps({"apply":APPLY,"before_count":len(artifacts),"before_bytes":sum(a["size_in_bytes"] for a in artifacts),"candidate_count":len(plan),"candidate_bytes":sum(a["bytes"] for a in plan),"kept_count":len(kept),"protected_runs":sorted(protected),"scanned_files":scan_count,"verified_release_binaries":len(verified)}),flush=True)
    deleted=[]
    if APPLY:
        approval=json.loads(Path(".github/storage-cleanup-approved.json").read_text())
        approved_ids=set(approval["ids"])
        plan=[a for a in plan if a["id"] in approved_ids]
        assert sum(a["bytes"] for a in plan)<=approval["bytes"]
        assert len(protected)>=4 and len(verified)>=2 and scan_count>=5
        for a in plan:
            api(f"repos/{REPO}/actions/artifacts/{a['id']}","DELETE")
            deleted.append(a)
        (out/"deleted.json").write_text(json.dumps(deleted,indent=2))
    remaining=pages(f"repos/{REPO}/actions/artifacts","artifacts")
    remaining_ids={a["id"] for a in remaining}
    assert not any(a["id"] in remaining_ids for a in deleted)
    assert all(a["id"] in remaining_ids for a in artifacts if a["workflow_run"]["id"] in protected and not a["expired"])
    final={"applied":APPLY,"deleted_count":len(deleted),"deleted_bytes":sum(a["bytes"] for a in deleted),"remaining_count":len(remaining),"remaining_bytes":sum(a["size_in_bytes"] for a in remaining)}
    (out/"summary.json").write_text(json.dumps(final,indent=2))
    print("FINAL_SUMMARY",json.dumps(final),flush=True)
    with open(os.environ["GITHUB_STEP_SUMMARY"],"a") as f:f.write("## Actions storage audit\n\n"+json.dumps(final)+"\n")
