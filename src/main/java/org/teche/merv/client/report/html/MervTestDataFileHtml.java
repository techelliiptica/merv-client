package org.teche.merv.client.report.html;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared CSS/JS and disk helpers for testdata file attachments in local suite HTML.
 */
public final class MervTestDataFileHtml {

    public static final int PREVIEW_LINE_COUNT = 10;
    public static final int OPEN_IN_TAB_LINE_THRESHOLD = 50;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** {@code MERV_OVERSIZE_FILE|<bytes>|<filename>} — stored in testdata when file is not uploaded. */
    public static final String OVERSIZE_TESTDATA_PREFIX = "MERV_OVERSIZE_FILE|";

    private MervTestDataFileHtml() {}

    public static long maxFileBytes() {
        return MAX_FILE_BYTES;
    }

    public static boolean isOversized(File source) {
        return source != null && source.exists() && source.length() > MAX_FILE_BYTES;
    }

    public static String oversizeTestdata(File source) {
        return OVERSIZE_TESTDATA_PREFIX + source.length() + "|" + source.getName();
    }

    /** Metadata stored on each local test step and in {@code merv-report.json}. */
    public static final class AttachedFile {
        private String path;
        private String originalName;
        private String fileExtension;
        private long fileSize;
        private String mimeType;
        private boolean sizeExceeded;

        public AttachedFile() {}

        public AttachedFile(String path, String originalName, String fileExtension, long fileSize, String mimeType) {
            this.path = path;
            this.originalName = originalName;
            this.fileExtension = fileExtension;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
        }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getOriginalName() { return originalName; }
        public void setOriginalName(String originalName) { this.originalName = originalName; }
        public String getFileExtension() { return fileExtension; }
        public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public boolean isSizeExceeded() { return sizeExceeded; }
        public void setSizeExceeded(boolean sizeExceeded) { this.sizeExceeded = sizeExceeded; }
    }

    private static AttachedFile metadataFor(File source, boolean sizeExceeded, String storedRelativePath) {
        String original = source.getName();
        String extension = extensionOf(original);
        AttachedFile meta = new AttachedFile();
        meta.setPath(storedRelativePath);
        meta.setOriginalName(original);
        meta.setFileExtension(extension);
        meta.setFileSize(source.length());
        meta.setMimeType(guessMimeType(extension));
        meta.setSizeExceeded(sizeExceeded);
        return meta;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Copies a testdata file into {@code {reportFolder}/attachments/} and returns metadata for JSON/HTML.
     */
    public static AttachedFile saveTestDataFile(File source, String reportFolderPath) {
        if (source == null || !source.exists() || reportFolderPath == null || reportFolderPath.isBlank()) {
            return null;
        }
        if (isOversized(source)) {
            return metadataFor(source, true, null);
        }
        try {
            String attachmentsDir = reportFolderPath + "attachments" + File.separator;
            File dir = new File(attachmentsDir);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }

            String original = source.getName();
            String extension = extensionOf(original);

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            String storedName = "testdata_" + timestamp + (extension.isEmpty() ? "" : "." + extension);
            File target = new File(attachmentsDir + storedName);
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            return metadataFor(source, false, "attachments" + File.separator + storedName);
        } catch (Exception e) {
            System.err.println("Could not save testdata file: " + e.getMessage());
            return null;
        }
    }

    public static boolean isFlatExtension(String extension) {
        if (extension == null) return false;
        String ext = extension.toLowerCase(Locale.ROOT);
        return "txt".equals(ext) || "json".equals(ext) || "csv".equals(ext) || "java".equals(ext);
    }

    public static boolean isImageExtension(String extension) {
        if (extension == null || extension.isBlank()) return false;
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico" -> true;
            default -> false;
        };
    }

    public static void appendStyles(StringBuilder html) {
        html.append(".testdata-files{margin:8px 0 0;display:flex;flex-direction:column;gap:10px;width:90%;max-width:90%;}");
        html.append(".testdata-file-bar{display:flex;align-items:center;gap:12px;padding:12px 14px;background:linear-gradient(135deg,#4a90d9 0%,#3b7fc4 100%);border-radius:10px;color:#fff;width:100%;box-sizing:border-box;}");
        html.append(".testdata-file-bar-icon{flex:0 0 36px;width:36px;height:36px;border-radius:8px;background:rgba(255,255,255,.15);display:flex;align-items:center;justify-content:center;font-size:18px;opacity:.9;}");
        html.append(".testdata-file-bar-info{flex:1;min-width:0;}");
        html.append(".testdata-file-bar-title{display:flex;align-items:center;gap:8px;min-width:0;}");
        html.append(".testdata-file-bar-name{font-weight:600;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}");
        html.append(".testdata-file-bar-badge{flex-shrink:0;font-size:10px;font-weight:700;padding:2px 6px;border-radius:4px;background:rgba(0,0,0,.2);color:rgba(255,255,255,.85);}");
        html.append(".testdata-file-bar-size{font-size:12px;color:rgba(255,255,255,.75);margin-top:2px;}");
        html.append(".testdata-file-bar-oversize{font-size:12px;color:rgba(255,255,255,.92);margin-top:4px;font-style:italic;}");
        html.append(".testdata-file-bar-actions{display:flex;gap:6px;flex-shrink:0;}");
        html.append(".testdata-file-bar-btn{border:none;background:transparent;color:#fff;width:34px;height:34px;border-radius:6px;cursor:pointer;font-size:16px;line-height:1;}");
        html.append(".testdata-file-bar-btn:hover{background:rgba(255,255,255,.18);}");
        html.append(".testdata-flat-file{border:1px solid #e6e8ec;border-radius:8px;background:#f7f8fa;padding:10px 12px;width:100%;box-sizing:border-box;}");
        html.append(".testdata-flat-file-head{display:flex;align-items:center;gap:8px;margin-bottom:8px;}");
        html.append(".testdata-flat-file-name{font-weight:600;font-size:13px;color:#333;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}");
        html.append(".testdata-flat-file-badge{font-size:10px;font-weight:700;padding:2px 6px;border-radius:4px;background:#e3f2fd;color:#1565c0;}");
        html.append(".testdata-flat-file-preview{margin:0;padding:8px 10px;background:#fff;border:1px solid #e9ecef;border-radius:6px;font-size:12px;line-height:1.45;max-height:220px;overflow:auto;white-space:pre-wrap;word-break:break-word;color:#333;}");
        html.append(".testdata-flat-file-link{display:inline-block;margin-top:8px;color:#007bff;font-size:13px;cursor:pointer;text-decoration:underline;}");
        html.append(".testdata-image-file{width:100%;box-sizing:border-box;}");
        html.append(".testdata-image-file-preview{display:block;max-width:100%;max-height:420px;width:auto;height:auto;border-radius:6px;border:1px solid #e9ecef;background:#fff;object-fit:contain;cursor:zoom-in;}");
        html.append(".merv-image-lightbox{display:none;position:fixed;inset:0;z-index:10000;}");
        html.append(".merv-image-lightbox.open{display:block;}");
        html.append(".merv-image-lightbox-backdrop{position:absolute;inset:0;background:rgba(0,0,0,.72);}");
        html.append(".merv-image-lightbox-panel{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:min(92vw,1100px);max-height:92vh;background:#fff;border-radius:10px;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 12px 40px rgba(0,0,0,.35);}");
        html.append(".merv-image-lightbox-header{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;border-bottom:1px solid #e9ecef;background:#f8f9fa;}");
        html.append(".merv-image-lightbox-title{font-weight:600;font-size:14px;color:#333;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding-right:8px;}");
        html.append(".merv-image-lightbox-close{border:none;background:transparent;font-size:22px;line-height:1;cursor:pointer;color:#666;padding:0 4px;}");
        html.append(".merv-image-lightbox-viewport{flex:1;overflow:hidden;display:flex;align-items:center;justify-content:center;min-height:280px;max-height:calc(92vh - 110px);background:#1a1a1a;padding:12px;cursor:grab;user-select:none;touch-action:none;}");
        html.append(".merv-image-lightbox-viewport.dragging{cursor:grabbing;}");
        html.append(".merv-image-lightbox-img{max-width:100%;max-height:100%;object-fit:contain;transform-origin:center center;transition:transform .15s ease;pointer-events:none;}");
        html.append(".merv-image-lightbox-toolbar{display:flex;align-items:center;justify-content:center;gap:10px;padding:10px 14px;border-top:1px solid #e9ecef;background:#f8f9fa;}");
        html.append(".merv-image-lightbox-toolbar button{border:1px solid #ced4da;background:#fff;border-radius:6px;padding:6px 12px;cursor:pointer;font-size:13px;}");
        html.append(".merv-image-lightbox-toolbar button:hover{background:#f1f3f5;}");
        html.append(".merv-image-lightbox-zoom-label{min-width:48px;text-align:center;font-weight:600;font-size:13px;color:#333;}");
    }

  /**
   * JS helpers used by the live suite report. Requires {@link #appendStyles(StringBuilder)} in the page CSS.
   */
    public static void appendScriptHelpers(StringBuilder html) {
        html.append("function fmtFileSize(n){n=+n||0;if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(2)+' KB';return(n/1048576).toFixed(2)+' MB';}");
        html.append("function isFlatExt(ext){ext=String(ext||'').toLowerCase();if(!ext)return false;return ext==='txt'||ext==='json'||ext==='csv'||ext==='java';}");
        html.append("function isImageExt(ext){ext=String(ext||'').toLowerCase();if(!ext)return false;return ext==='png'||ext==='jpg'||ext==='jpeg'||ext==='gif'||ext==='bmp'||ext==='webp'||ext==='svg'||ext==='ico';}");
        html.append("function fileUrl(rel){return ('../'+String(rel||'')).replace(/\\\\/g,'/');}");
        html.append("function splitLines(t){return String(t||'').replace(/\\r\\n/g,'\\n').replace(/\\r/g,'\\n').split('\\n');}");
        html.append("function renderOversizeFileBar(af){var name=af.originalName||'file';var ext=String(af.fileExtension||'').toUpperCase()||'FILE';var size=fmtFileSize(af.fileSize);var h='<div class=\"testdata-file-bar\">';h+='<div class=\"testdata-file-bar-icon\">&#128196;</div>';h+='<div class=\"testdata-file-bar-info\"><div class=\"testdata-file-bar-title\"><span class=\"testdata-file-bar-name\" title=\"'+e(name)+'\">'+e(name)+'</span><span class=\"testdata-file-bar-badge\">'+e(ext)+'</span></div>';if(size)h+='<div class=\"testdata-file-bar-size\">'+e(size)+'</div>';h+='<div class=\"testdata-file-bar-oversize\">Download not available due to size exceed 20MB</div>';h+='</div></div>';return h;}");
        html.append("function renderBinaryFileBar(af,url){var name=af.originalName||'file';var ext=String(af.fileExtension||'').toUpperCase()||'FILE';var size=fmtFileSize(af.fileSize);var h='<div class=\"testdata-file-bar\">';h+='<div class=\"testdata-file-bar-icon\">&#128196;</div>';h+='<div class=\"testdata-file-bar-info\"><div class=\"testdata-file-bar-title\"><span class=\"testdata-file-bar-name\" title=\"'+e(name)+'\">'+e(name)+'</span><span class=\"testdata-file-bar-badge\">'+e(ext)+'</span></div>';if(size)h+='<div class=\"testdata-file-bar-size\">'+e(size)+'</div>';h+='</div><div class=\"testdata-file-bar-actions\">';h+='<button type=\"button\" class=\"testdata-file-bar-btn\" data-dl=\"'+e(url)+'\" data-name=\"'+e(name)+'\" title=\"Download\">&#8681;</button>';h+='<button type=\"button\" class=\"testdata-file-bar-btn\" data-open=\"'+e(url)+'\" title=\"Open in new tab\">&#8599;</button>';h+='</div></div>';return h;}");
        html.append("function renderImageFileBlock(af,url){var name=af.originalName||'file';return '<div class=\"testdata-image-file\"><img class=\"testdata-image-file-preview merv-zoomable-image\" src=\"'+e(url)+'\" alt=\"'+e(name)+'\" loading=\"lazy\" data-img-url=\"'+e(url)+'\" data-img-name=\"'+e(name)+'\" title=\"Click to zoom\"/></div>';}");
        html.append("var _imgLbZoom=1,_imgLbPanX=0,_imgLbPanY=0,_imgLbDrag=null;function applyImageLightboxTransform(){var img=document.querySelector('#merv-image-lightbox .merv-image-lightbox-img');if(img)img.style.transform='translate('+_imgLbPanX+'px,'+_imgLbPanY+'px) scale('+_imgLbZoom+')';}");
        html.append("function setImageLightboxZoom(z){_imgLbZoom=Math.max(0.25,Math.min(4,z));applyImageLightboxTransform();var lbl=document.querySelector('#merv-image-lightbox .merv-image-lightbox-zoom-label');if(lbl)lbl.textContent=Math.round(_imgLbZoom*100)+'%';}");
        html.append("function resetImageLightboxView(){_imgLbZoom=1;_imgLbPanX=0;_imgLbPanY=0;applyImageLightboxTransform();var lbl=document.querySelector('#merv-image-lightbox .merv-image-lightbox-zoom-label');if(lbl)lbl.textContent='100%';}");
        html.append("function wireImageLightboxDrag(vp){if(vp._mervDragWired)return;vp._mervDragWired=true;vp.addEventListener('pointerdown',function(ev){if(ev.button!==0)return;_imgLbDrag={sx:ev.clientX,sy:ev.clientY,px:_imgLbPanX,py:_imgLbPanY};vp.setPointerCapture(ev.pointerId);vp.classList.add('dragging');var img=vp.querySelector('.merv-image-lightbox-img');if(img)img.style.transition='none';ev.preventDefault();});vp.addEventListener('pointermove',function(ev){if(!_imgLbDrag)return;_imgLbPanX=_imgLbDrag.px+(ev.clientX-_imgLbDrag.sx);_imgLbPanY=_imgLbDrag.py+(ev.clientY-_imgLbDrag.sy);applyImageLightboxTransform();ev.preventDefault();});var endLbDrag=function(ev){if(!_imgLbDrag)return;_imgLbDrag=null;vp.classList.remove('dragging');var img=vp.querySelector('.merv-image-lightbox-img');if(img)img.style.transition='';try{vp.releasePointerCapture(ev.pointerId);}catch(ex){}};vp.addEventListener('pointerup',endLbDrag);vp.addEventListener('pointercancel',endLbDrag);}");
        html.append("function closeImageLightbox(){var lb=document.getElementById('merv-image-lightbox');if(lb)lb.classList.remove('open');}");
        html.append("function ensureImageLightbox(){var lb=document.getElementById('merv-image-lightbox');if(lb)return lb;lb=document.createElement('div');lb.id='merv-image-lightbox';lb.className='merv-image-lightbox';lb.innerHTML='<div class=\"merv-image-lightbox-backdrop\"></div><div class=\"merv-image-lightbox-panel\"><div class=\"merv-image-lightbox-header\"><span class=\"merv-image-lightbox-title\"></span><button type=\"button\" class=\"merv-image-lightbox-close\" title=\"Close\">&times;</button></div><div class=\"merv-image-lightbox-viewport\"><img class=\"merv-image-lightbox-img\" alt=\"\"/></div><div class=\"merv-image-lightbox-toolbar\"><button type=\"button\" class=\"merv-image-lightbox-zoom-out\" title=\"Zoom out\">&#8722;</button><span class=\"merv-image-lightbox-zoom-label\">100%</span><button type=\"button\" class=\"merv-image-lightbox-zoom-in\" title=\"Zoom in\">+</button><button type=\"button\" class=\"merv-image-lightbox-zoom-reset\" title=\"Reset\">Reset</button></div></div>';document.body.appendChild(lb);lb.querySelector('.merv-image-lightbox-backdrop').onclick=closeImageLightbox;lb.querySelector('.merv-image-lightbox-close').onclick=closeImageLightbox;lb.querySelector('.merv-image-lightbox-zoom-in').onclick=function(){setImageLightboxZoom(_imgLbZoom+0.25);};lb.querySelector('.merv-image-lightbox-zoom-out').onclick=function(){setImageLightboxZoom(_imgLbZoom-0.25);};lb.querySelector('.merv-image-lightbox-zoom-reset').onclick=resetImageLightboxView;wireImageLightboxDrag(lb.querySelector('.merv-image-lightbox-viewport'));return lb;}");
        html.append("function openImageLightbox(url,name){var lb=ensureImageLightbox();resetImageLightboxView();lb.querySelector('.merv-image-lightbox-title').textContent=name||'Image';var img=lb.querySelector('.merv-image-lightbox-img');img.src=url;img.alt=name||'Image';lb.classList.add('open');}");
        html.append("function renderFlatFileBlock(af,url){var id='flat-'+Math.random().toString(36).slice(2);var ext=String(af.fileExtension||'').toUpperCase()||'FILE';var name=af.originalName||'file';var h='<div class=\"testdata-flat-file\" id=\"'+id+'\" data-url=\"'+e(url)+'\"><div class=\"testdata-flat-file-head\"><span class=\"testdata-flat-file-name\" title=\"'+e(name)+'\">'+e(name)+'</span><span class=\"testdata-flat-file-badge\">'+e(ext)+'</span></div><pre class=\"testdata-flat-file-preview\">Loading…</pre></div>';fetch(url).then(function(r){return r.text();}).then(function(txt){var box=document.getElementById(id);if(!box)return;var pre=box.querySelector('.testdata-flat-file-preview');if(!pre)return;var lines=splitLines(txt);var preview=lines.slice(0,").append(PREVIEW_LINE_COUNT).append(").join('\\n');pre.textContent=preview;box._fullText=txt;box._lineCount=lines.length;if(lines.length>").append(PREVIEW_LINE_COUNT).append("||lines.length>").append(OPEN_IN_TAB_LINE_THRESHOLD).append("){var link=document.createElement('span');link.className='testdata-flat-file-link';link.textContent=lines.length>").append(OPEN_IN_TAB_LINE_THRESHOLD).append("?'Click to see full file (opens in new tab)':'Click to see full file';link.onclick=function(ev){ev.preventDefault();if(box._lineCount>").append(OPEN_IN_TAB_LINE_THRESHOLD).append("){window.open(url,'_blank');return;}pre.textContent=box._expanded?preview:box._fullText;link.textContent=box._expanded?'Click to see full file':'Show less';box._expanded=!box._expanded;};box.appendChild(link);}}).catch(function(){var box=document.getElementById(id);if(!box)return;var pre=box.querySelector('.testdata-flat-file-preview');if(pre)pre.textContent='Could not load file preview.';});return h;}");
        html.append("function renderAttachedFiles(st){var files=(st&&st.attachedFiles)||[];if(!files.length)return'';var h='<div class=\"testdata-files\">';files.forEach(function(af){if(!af)return;if(af.sizeExceeded){h+=renderOversizeFileBar(af);return;}if(!af.path)return;var url=fileUrl(af.path);if(isImageExt(af.fileExtension))h+=renderImageFileBlock(af,url);else if(isFlatExt(af.fileExtension))h+=renderFlatFileBlock(af,url);else h+=renderBinaryFileBar(af,url);});h+='</div>';return h;}");
        html.append("document.addEventListener('click',function(ev){var zi=ev.target&&ev.target.closest?ev.target.closest('.merv-zoomable-image'):null;if(zi){ev.preventDefault();openImageLightbox(zi.getAttribute('data-img-url')||zi.src,zi.getAttribute('data-img-name')||zi.alt);return;}var dl=ev.target&&ev.target.closest?ev.target.closest('[data-dl]'):null;if(dl){ev.preventDefault();var u=dl.getAttribute('data-dl');var n=dl.getAttribute('data-name')||'file';var a=document.createElement('a');a.href=u;a.download=n;a.click();return;}var op=ev.target&&ev.target.closest?ev.target.closest('[data-open]'):null;if(op){ev.preventDefault();window.open(op.getAttribute('data-open'),'_blank');}});");
        html.append("document.addEventListener('keydown',function(ev){if(ev.key==='Escape')closeImageLightbox();});");
    }

    private static String guessMimeType(String extension) {
        if (extension == null) return "application/octet-stream";
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "txt" -> "text/plain";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "pdf" -> "application/pdf";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }
}
