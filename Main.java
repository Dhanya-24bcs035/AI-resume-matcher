import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.InflaterInputStream;

/**
 * ============================================================
 *  AI RESUME MATCHER & CAREER FINDER — PURE JAVA
 * ============================================================
 * Features:
 *  - Recruiter Mode: Match Resumes to a Job Description
 *  - Job Seeker Mode (Career Finder): Upload Resume PDF → Discover Best-Fitting Jobs & Skill Gap Advice!
 *  - Interactive Multi-File Queue Uploader (select/add files in batches)
 *  - Pure Java PDF Text Extractor (InflaterInputStream)
 *  - Dynamic Skill Priority Weighting
 *  - Side-by-Side Candidate Skill Comparison Matrix
 *  - Technical Interview Probe Generator
 *  - Executive Shortlist Report Exporter
 * ============================================================
 */
public class Main {

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", new FormPageHandler());
        server.createContext("/match", new MatchHandler());
        server.createContext("/sample-pdf", new SamplePdfHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("AI Resume Matcher Server running at http://localhost:" + port);
    }

    // ============================================================
    //  HTTP HANDLERS
    // ============================================================

    static class FormPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String html = HtmlPages.formPage(HtmlPages.SAMPLE_JOB, HtmlPages.SAMPLE_RESUMES);
            sendHtml(exchange, 200, html);
        }
    }

    static class SamplePdfHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String name = "Arjun_Mehta_Resume.pdf";
            String text = "Name: Arjun Mehta\nBackend engineer with 4 years of experience building Java applications using Spring Boot, microservices, PostgreSQL, Docker, Kubernetes, AWS, Kafka, and Jenkins.";
            byte[] pdfBytes = PdfGenerator.createSimplePdf(text);
            
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            exchange.sendResponseHeaders(200, pdfBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(pdfBytes);
            }
        }
    }

    static class MatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            List<Candidate> candidates = new ArrayList<>();
            String jobTitle = "Backend Java Developer";
            String jobDescription = "";
            List<String> weightedSkills = new ArrayList<>();
            String mode = "recruiter";

            if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
                MultipartParser.ParseResult parsed = MultipartParser.parse(exchange, contentType);
                mode = parsed.getFormFields().getOrDefault("appMode", "recruiter");
                jobTitle = parsed.getFormFields().getOrDefault("jobTitle", "Backend Java Developer");
                jobDescription = parsed.getFormFields().getOrDefault("jobDescription", "");
                
                String weightsStr = parsed.getFormFields().getOrDefault("prioritySkills", "");
                if (!weightsStr.isBlank()) {
                    weightedSkills = Arrays.stream(weightsStr.split(","))
                            .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toList());
                }

                String pastedResumes = parsed.getFormFields().getOrDefault("resumesBlob", "");
                candidates.addAll(parseResumesBlob(pastedResumes));
                candidates.addAll(parsed.getCandidatesFromFiles());
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> form = parseFormBody(body);
                mode = form.getOrDefault("appMode", "recruiter");
                jobTitle = form.getOrDefault("jobTitle", "Backend Java Developer");
                jobDescription = form.getOrDefault("jobDescription", "");
                String resumesBlob = form.getOrDefault("resumesBlob", "");
                candidates.addAll(parseResumesBlob(resumesBlob));
            }

            JobPosting job = new JobPosting(jobTitle, jobDescription);

            String html;
            if (candidates.isEmpty() && "recruiter".equals(mode) && jobDescription.isBlank()) {
                html = HtmlPages.errorPage("Please provide a job description and upload at least one PDF/TXT resume or paste candidate text.");
            } else if (candidates.isEmpty()) {
                html = HtmlPages.errorPage("Please upload at least one PDF or TXT resume file to find suitable jobs!");
            } else {
                ResumeMatcherEngine engine = new ResumeMatcherEngine();
                List<MatchResult> results = engine.rankCandidates(job, candidates, weightedSkills);
                List<String> topSkills = engine.extractTopSkills(jobDescription.isBlank() ? "Java Spring Boot SQL Cloud" : jobDescription);

                // Calculate Career Job Recommendations for each candidate
                Map<Candidate, List<JobRecommendation>> careerRecs = new LinkedHashMap<>();
                for (Candidate c : candidates) {
                    careerRecs.put(c, engine.recommendJobsForCandidate(c));
                }

                html = HtmlPages.resultsPage(job, results, topSkills, careerRecs, mode);
            }

            sendHtml(exchange, 200, html);
        }
    }

    private static void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static List<Candidate> parseResumesBlob(String blob) {
        List<Candidate> candidates = new ArrayList<>();
        if (blob == null || blob.isBlank()) return candidates;

        String[] blocks = blob.split("(?m)^\\s*===\\s*$");
        int counter = 1;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;

            String name = "Candidate " + counter;
            String resumeText = trimmed;

            String[] lines = trimmed.split("\\R", 2);
            if (lines[0].toLowerCase().startsWith("name:")) {
                name = lines[0].substring(5).trim();
                resumeText = lines.length > 1 ? lines[1].trim() : "";
            }

            candidates.add(new Candidate("C" + String.format("%03d", counter), name, resumeText));
            counter++;
        }
        return candidates;
    }
}

// ============================================================
//  MULTIPART & PDF PARSER (PURE JAVA)
// ============================================================

class MultipartParser {

    public static class ParseResult {
        private final Map<String, String> formFields = new HashMap<>();
        private final List<Candidate> candidatesFromFiles = new ArrayList<>();

        public Map<String, String> getFormFields() { return formFields; }
        public List<Candidate> getCandidatesFromFiles() { return candidatesFromFiles; }
    }

    public static ParseResult parse(HttpExchange exchange, String contentType) throws IOException {
        ParseResult result = new ParseResult();
        String boundary = null;
        for (String param : contentType.split(";")) {
            param = param.trim();
            if (param.toLowerCase().startsWith("boundary=")) {
                boundary = param.substring(9).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null) return result;

        byte[] body = exchange.getRequestBody().readAllBytes();
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        List<Integer> positions = findBoundaryPositions(body, boundaryBytes);
        int fileCount = 1;

        for (int i = 0; i < positions.size() - 1; i++) {
            int start = positions.get(i) + boundaryBytes.length;
            int end = positions.get(i + 1);

            if (start >= end) continue;
            byte[] partBytes = Arrays.copyOfRange(body, start, end);

            int headerEnd = findHeaderEnd(partBytes);
            if (headerEnd == -1) continue;

            String headers = new String(partBytes, 0, headerEnd, StandardCharsets.UTF_8);
            byte[] content = Arrays.copyOfRange(partBytes, headerEnd + 4, partBytes.length - 2);

            String name = extractHeaderParam(headers, "name");
            String filename = extractHeaderParam(headers, "filename");

            if (filename != null && !filename.isBlank()) {
                String candidateName = cleanCandidateNameFromFilename(filename);
                String extractedText = "";

                if (filename.toLowerCase().endsWith(".pdf")) {
                    extractedText = PdfTextExtractor.extractText(content);
                } else {
                    extractedText = new String(content, StandardCharsets.UTF_8);
                }

                if (!extractedText.isBlank()) {
                    result.candidatesFromFiles.add(new Candidate("F" + (fileCount++), candidateName, extractedText));
                }
            } else if (name != null) {
                String value = new String(content, StandardCharsets.UTF_8);
                result.formFields.put(name, value);
            }
        }

        return result;
    }

    private static String cleanCandidateNameFromFilename(String filename) {
        String base = filename;
        int idx = base.lastIndexOf('.');
        if (idx > 0) base = base.substring(0, idx);
        base = base.replaceAll("[-_]", " ").replaceAll("(?i)\\bresume\\b", "").trim();
        if (base.isBlank()) return "Uploaded Candidate";
        String[] words = base.split("\\s+");
        return Arrays.stream(words)
                .map(w -> w.substring(0, 1).toUpperCase() + (w.length() > 1 ? w.substring(1) : ""))
                .collect(Collectors.joining(" "));
    }

    private static List<Integer> findBoundaryPositions(byte[] data, byte[] target) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i <= data.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) { match = false; break; }
            }
            if (match) res.add(i);
        }
        return res;
    }

    private static int findHeaderEnd(byte[] part) {
        for (int i = 0; i < part.length - 3; i++) {
            if (part[i] == '\r' && part[i+1] == '\n' && part[i+2] == '\r' && part[i+3] == '\n') return i;
        }
        return -1;
    }

    private static String extractHeaderParam(String header, String param) {
        Pattern pattern = Pattern.compile(param + "=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(header);
        if (matcher.find()) return matcher.group(1);
        return null;
    }
}

class PdfTextExtractor {

    public static String extractText(byte[] pdfBytes) {
        StringBuilder sb = new StringBuilder();
        try {
            List<byte[]> streams = extractStreams(pdfBytes);
            for (byte[] streamData : streams) {
                String text = parsePdfStreamText(streamData);
                if (!text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append(extractRawAscii(pdfBytes));
        }

        if (sb.toString().isBlank()) {
            return extractRawAscii(pdfBytes);
        }
        return sb.toString();
    }

    private static List<byte[]> extractStreams(byte[] pdf) {
        List<byte[]> streams = new ArrayList<>();
        String pdfStr = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = 0;

        while ((pos = pdfStr.indexOf("stream", pos)) != -1) {
            int streamStart = pos + 6;
            if (streamStart < pdf.length && pdf[streamStart] == '\r') streamStart++;
            if (streamStart < pdf.length && pdf[streamStart] == '\n') streamStart++;

            int endPos = pdfStr.indexOf("endstream", streamStart);
            if (endPos == -1) break;

            byte[] streamData = Arrays.copyOfRange(pdf, streamStart, endPos);
            
            int dictStart = Math.max(0, pos - 300);
            String dictStr = pdfStr.substring(dictStart, pos);
            if (dictStr.contains("/FlateDecode")) {
                byte[] decompressed = inflate(streamData);
                if (decompressed.length > 0) streamData = decompressed;
            }

            streams.add(streamData);
            pos = endPos + 9;
        }
        return streams;
    }

    private static byte[] inflate(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             InflaterInputStream iis = new InflaterInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = iis.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String parsePdfStreamText(byte[] data) {
        String content = new String(data, StandardCharsets.ISO_8859_1);
        StringBuilder text = new StringBuilder();

        Matcher mParentheses = Pattern.compile("\\(([^)]+)\\)\\s*(?:Tj|'|\")").matcher(content);
        while (mParentheses.find()) {
            text.append(mParentheses.group(1)).append(" ");
        }

        Matcher mArray = Pattern.compile("\\[((?:[^\\s\\[\\]]+|\\([^)]+\\)|\\s+)+)\\]\\s*TJ").matcher(content);
        while (mArray.find()) {
            String arrayContent = mArray.group(1);
            Matcher innerStr = Pattern.compile("\\(([^)]+)\\)").matcher(arrayContent);
            while (innerStr.find()) {
                text.append(innerStr.group(1));
            }
            text.append(" ");
        }

        return text.toString().replaceAll("\\\\\\)", ")").replaceAll("\\\\\\(", "(").trim();
    }

    private static String extractRawAscii(byte[] pdfBytes) {
        String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        StringBuilder clean = new StringBuilder();
        Matcher matcher = Pattern.compile("[a-zA-Z0-9+#.,;:!?'\"()\\-\\s]{4,}").matcher(raw);
        while (matcher.find()) {
            String match = matcher.group(0).trim();
            if (!match.contains("obj") && !match.contains("endobj") && !match.contains("stream") && !match.contains("FlateDecode")) {
                clean.append(match).append("\n");
            }
        }
        return clean.toString();
    }
}

class PdfGenerator {
    public static byte[] createSimplePdf(String text) {
        String streamContent = "BT /F1 12 Tf 50 750 Td (" + text.replace("\n", ") Tj T* (") + ") Tj ET";
        byte[] streamBytes = streamContent.getBytes(StandardCharsets.ISO_8859_1);
        
        String pdfHeader = "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
                + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n"
                + "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n"
                + "5 0 obj << /Length " + streamBytes.length + " >> stream\n";
        String pdfFooter = "\nendstream\nendobj\nxref\n0 6\n0000000000 65535 f \n"
                + "0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n0000000243 00000 n \n0000000312 00000 n \n"
                + "trailer << /Size 6 /Root 1 0 R >>\nstartxref\n" + (312 + streamBytes.length + 20) + "\n%%EOF";
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(pdfHeader.getBytes(StandardCharsets.ISO_8859_1));
            baos.write(streamBytes);
            baos.write(pdfFooter.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }
}

// ============================================================
//  HTML PAGES & DASHBOARD
// ============================================================

class HtmlPages {

    static final String SAMPLE_JOB =
            "We are looking for a Backend Java Developer with strong experience in " +
            "Java, Spring Boot, and microservices architecture. The ideal candidate " +
            "has hands-on experience with REST APIs, SQL databases such as PostgreSQL, " +
            "Docker, Kubernetes, and cloud platforms like AWS. Familiarity with Kafka " +
            "for event-driven systems and CI/CD pipelines using Jenkins is a big plus. " +
            "Good understanding of unit testing with JUnit and agile methodologies is required.";

    static final String SAMPLE_RESUMES =
            "Name: Arjun Mehta\n" +
            "Backend engineer with 4 years of experience building Java applications " +
            "using Spring Boot and microservices. Designed and deployed REST APIs backed " +
            "by PostgreSQL, containerized with Docker and orchestrated on Kubernetes. " +
            "Experience with AWS, Kafka for event streaming, Jenkins CI/CD pipelines, and " +
            "JUnit for testing. Comfortable working in agile scrum teams.\n" +
            "===\n" +
            "Name: Priya Nair\n" +
            "Frontend developer skilled in React, JavaScript, HTML and CSS. Built " +
            "responsive user interfaces and worked closely with backend teams. Some " +
            "exposure to REST APIs and Git. Currently learning TypeScript and Next.js.\n" +
            "===\n" +
            "Name: Rahul Sharma\n" +
            "Java developer with 2 years building enterprise applications. Worked with " +
            "Spring Framework and Hibernate for database access against MySQL. Basic " +
            "exposure to Docker. No cloud or Kubernetes experience yet, but eager to " +
            "learn AWS and event-driven systems.\n" +
            "===\n" +
            "Name: Sara Thomas\n" +
            "Data analyst with strong Python and SQL skills. Experience with Pandas, " +
            "data visualization, and building dashboards. Limited software engineering " +
            "background and no Java experience.";

    private static final String STYLE = """
        <style>
          :root {
            --bg: #0b0d12; --panel: #131722; --card-bg: #1a202c; --border: #2d3748;
            --text: #edf2f7; --muted: #a0aec0; --accent: #ff6a3d;
            --good: #3ddc84; --warn: #ffb454; --danger: #ff6b6b;
            --glow: rgba(255, 106, 61, 0.15);
          }
          * { box-sizing: border-box; }
          body {
            background: var(--bg); color: var(--text);
            font-family: -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
            margin: 0; padding: 40px 20px;
          }
          .container { max-width: 980px; margin: 0 auto; }
          .header-box { text-align: center; margin-bottom: 25px; }
          h1 { font-size: 34px; font-weight: 800; background: linear-gradient(135deg, #ff6a3d, #ff9d76); -webkit-background-clip: text; -webkit-text-fill-color: transparent; margin-bottom: 8px; }
          p.subtitle { color: var(--muted); font-size: 15px; margin-top: 0; }
          
          /* App Mode Switcher */
          .mode-switcher { display: flex; justify-content: center; gap: 12px; margin-bottom: 25px; }
          .mode-btn { background: var(--panel); border: 1px solid var(--border); color: var(--muted); padding: 10px 20px; border-radius: 20px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s; }
          .mode-btn.active { background: var(--accent); color: #fff; border-color: var(--accent); box-shadow: 0 4px 12px var(--glow); }

          .tab-nav { display: flex; gap: 10px; border-bottom: 1px solid var(--border); margin-bottom: 24px; }
          .tab-btn { background: none; border: none; color: var(--muted); padding: 12px 18px; font-size: 15px; font-weight: 600; cursor: pointer; border-bottom: 2px solid transparent; transition: all 0.2s; }
          .tab-btn.active { color: var(--accent); border-bottom-color: var(--accent); }

          /* File Queue Container */
          .dropzone {
            border: 2px dashed var(--accent); background: rgba(255, 106, 61, 0.04);
            border-radius: 12px; padding: 24px; text-align: center; cursor: pointer; transition: background 0.2s;
            margin-bottom: 15px;
          }
          .dropzone:hover { background: rgba(255, 106, 61, 0.08); }
          .file-queue { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin-top: 15px; }
          .file-card {
            background: var(--panel); border: 1px solid var(--border); border-radius: 8px;
            padding: 10px 14px; display: flex; justify-content: space-between; align-items: center; font-size: 13px;
          }
          .file-card .fname { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 140px; }
          .file-card .fsize { color: var(--muted); font-size: 11px; }
          .btn-remove { background: none; border: none; color: var(--danger); font-weight: bold; cursor: pointer; font-size: 14px; padding: 2px 6px; }

          label { display:block; font-weight:600; margin: 18px 0 6px; font-size: 14px; }
          input[type=text], textarea {
            width: 100%; background: var(--panel); color: var(--text);
            border: 1px solid var(--border); border-radius: 8px;
            padding: 12px; font-size: 14px; font-family: inherit; resize: vertical;
          }
          textarea { min-height: 110px; line-height: 1.5; }
          .hint { color: var(--muted); font-size: 12px; margin-top: 6px; }
          
          button.btn-primary {
            background: linear-gradient(135deg, #ff6a3d, #e05326); color: #fff; border: none;
            padding: 14px 28px; border-radius: 8px; font-size: 16px; font-weight: 700;
            cursor: pointer; box-shadow: 0 4px 14px var(--glow); transition: transform 0.1s, opacity 0.2s;
          }
          button.btn-primary:hover { opacity: 0.95; transform: translateY(-1px); }

          /* Fit Ratings & Badges */
          .card {
            background: var(--panel); border: 1px solid var(--border);
            border-radius: 12px; padding: 22px; margin-bottom: 18px; position: relative;
          }
          .card-header { display: flex; justify-content: space-between; align-items: center; }
          .card h3 { margin: 0; font-size: 20px; display: flex; align-items: center; gap: 10px; }
          
          .tier-badge { display: inline-block; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 700; margin-left: 8px; }
          .tier-badge.top { background: rgba(61, 220, 132, 0.15); color: var(--good); border: 1px solid var(--good); }
          .tier-badge.strong { background: rgba(255, 180, 84, 0.15); color: var(--warn); border: 1px solid var(--warn); }
          .tier-badge.low { background: rgba(255, 107, 107, 0.15); color: var(--danger); border: 1px solid var(--danger); }

          .score-ring { font-size: 26px; font-weight: 800; }
          .score-ring.high { color: var(--good); }
          .score-ring.mid { color: var(--warn); }
          .score-ring.low { color: var(--danger); }

          .fit-grid { display: flex; gap: 14px; margin: 12px 0; font-size: 12px; color: var(--muted); }
          .fit-item { background: #181d28; padding: 6px 12px; border-radius: 6px; border: 1px solid var(--border); }
          .fit-item b { color: var(--text); }

          .tag {
            display:inline-block; background: #1a202c; border: 1px solid var(--border);
            border-radius: 6px; padding: 3px 8px; margin: 3px 4px 0 0; font-size: 12px;
          }
          .tag.matched { border-color: var(--good); color: var(--good); background: rgba(61, 220, 132, 0.08); }
          .tag.missing { border-color: rgba(255, 107, 107, 0.4); color: #ff9d9d; background: rgba(255, 107, 107, 0.05); }

          /* Matrix Table */
          .matrix-table { width: 100%; border-collapse: collapse; margin-top: 15px; }
          .matrix-table th, .matrix-table td { border: 1px solid var(--border); padding: 10px 14px; text-align: center; font-size: 14px; }
          .matrix-table th { background: var(--card-bg); color: var(--muted); text-align: left; }
          .check-yes { color: var(--good); font-weight: bold; }
          .check-no { color: var(--danger); font-weight: bold; }

          .probe-box { background: #181d28; border-left: 3px solid var(--accent); padding: 12px 16px; margin-top: 12px; border-radius: 0 8px 8px 0; font-size: 13px; }
          .probe-box b { color: var(--accent); }

          .job-rec-card { background: #181d28; border: 1px solid var(--border); border-radius: 8px; padding: 14px 18px; margin-bottom: 10px; }
          .job-rec-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
          .job-title-text { font-size: 16px; font-weight: 700; color: var(--text); }

          a.back { color: var(--muted); text-decoration: none; font-size: 14px; display: inline-block; margin-bottom: 20px; }
          a.back:hover { color: var(--text); }
          .error { color: var(--danger); background: #2a1717; border:1px solid #4a2a2a; padding: 16px; border-radius: 8px; }
        </style>
        """;

    static String formPage(String jobDescriptionValue, String resumesValue) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<title>AI Resume Matcher & Career Finder</title>" + STYLE + "</head><body>"
                + "<div class='container'>"
                + "<div class='header-box'>"
                + "<h1>AI Resume Matcher & Career Finder</h1>"
                + "<p class='subtitle'>Match candidates to job descriptions OR upload your resume PDF to discover top recommended job roles suited for you!</p>"
                + "</div>"
                
                + "<div class='mode-switcher'>"
                + "<button type='button' id='btnRecruiter' class='mode-btn active' onclick='setAppMode(\"recruiter\")'>🎯 Recruiter Mode (Match Job Description)</button>"
                + "<button type='button' id='btnSeeker' class='mode-btn' onclick='setAppMode(\"seeker\")'>🚀 Job Seeker Mode (Find Suitable Jobs)</button>"
                + "</div>"

                + "<form id='matchForm' method='POST' action='/match' enctype='multipart/form-data'>"
                + "<input type='hidden' id='appMode' name='appMode' value='recruiter'>"
                
                + "<div id='recruiterFields'>"
                + "<label>Job Title</label>"
                + "<input type='text' name='jobTitle' value='Backend Java Developer'>"
                + "<label>Job Description</label>"
                + "<textarea name='jobDescription' rows='4'>" + escape(jobDescriptionValue) + "</textarea>"
                + "<label>🎯 Optional: Priority Must-Have Skills (Comma Separated for 2x Weight)</label>"
                + "<input type='text' name='prioritySkills' placeholder='e.g. Spring Boot, PostgreSQL, Docker' value='Spring Boot, PostgreSQL'>"
                + "<div class='hint'>Priority skills count 2x in scoring calculation!</div>"
                + "</div>"

                + "<div id='seekerHeader' style='display:none;' class='card'>"
                + "<h3>🚀 Career Finder Mode Active</h3>"
                + "<p class='subtitle'>Upload your resume PDF below. Our AI engine will analyze your skills against top tech role profiles and recommend the best matching jobs for your career!</p>"
                + "</div>"

                + "<label>📁 Upload Resume File(s) (PDF or TXT)</label>"
                + "<div class='dropzone' onclick='document.getElementById(\"filePicker\").click()'>"
                + "<div style='font-size:32px; margin-bottom:6px;'>📄</div>"
                + "<div><b>Click to Select PDF / TXT Resumes</b> (Select multiple files or add in batches)</div>"
                + "<div class='hint'>Upload one or more candidate resumes to analyze!</div>"
                + "<input type='file' id='filePicker' multiple accept='.pdf,.txt' style='display:none;' onchange='handleFiles(this.files)'>"
                + "</div>"

                + "<div id='queueTitle' style='display:none; font-weight:600; font-size:14px; margin-bottom:8px;'>Selected Files Queue (<span id='fileCount'>0</span>):</div>"
                + "<div id='fileQueue' class='file-queue'></div>"
                
                + "<div style='text-align:right; font-size:12px; margin-top:8px;'><a href='/sample-pdf' style='color:var(--accent);'>Download Sample PDF Resume for Testing</a></div>"

                + "<div id='pasteSection'>"
                + "<label>Or Paste Raw Resumes (Fallback Text Format)</label>"
                + "<textarea name='resumesBlob' rows='5'>" + escape(resumesValue) + "</textarea>"
                + "<div class='hint'>Separate pasted resumes with <code>===</code> and <code>Name: Candidate Name</code></div>"
                + "</div>"

                + "<br><button type='submit' id='submitBtn' class='btn-primary'>🚀 Run Match & Analysis</button>"
                + "</form>"
                + "</div>"
                
                + "<script>"
                + "let selectedFiles = [];"
                + "function setAppMode(mode) {"
                + "  document.getElementById('appMode').value = mode;"
                + "  document.getElementById('btnRecruiter').classList.toggle('active', mode==='recruiter');"
                + "  document.getElementById('btnSeeker').classList.toggle('active', mode==='seeker');"
                + "  document.getElementById('recruiterFields').style.display = mode==='recruiter' ? 'block' : 'none';"
                + "  document.getElementById('seekerHeader').style.display = mode==='seeker' ? 'block' : 'none';"
                + "  document.getElementById('submitBtn').innerText = mode==='seeker' ? '🚀 Find Recommended Jobs for My Resume' : '🚀 Run Multi-Resume Match Analysis';"
                + "}"
                + "function handleFiles(files) {"
                + "  for(let file of files) {"
                + "    if(!selectedFiles.some(f => f.name === file.name && f.size === file.size)) {"
                + "      selectedFiles.push(file);"
                + "    }"
                + "  }"
                + "  renderQueue();"
                + "}"
                + "function removeFile(index) {"
                + "  selectedFiles.splice(index, 1);"
                + "  renderQueue();"
                + "}"
                + "function renderQueue() {"
                + "  const queueEl = document.getElementById('fileQueue');"
                + "  const countEl = document.getElementById('fileCount');"
                + "  const titleEl = document.getElementById('queueTitle');"
                + "  queueEl.innerHTML = '';"
                + "  countEl.innerText = selectedFiles.length;"
                + "  titleEl.style.display = selectedFiles.length > 0 ? 'block' : 'none';"
                + "  selectedFiles.forEach((file, idx) => {"
                + "    const size = (file.size / 1024).toFixed(1) + ' KB';"
                + "    const card = document.createElement('div');"
                + "    card.className = 'file-card';"
                + "    card.innerHTML = `<div><div class='fname'>📄 ${file.name}</div><div class='fsize'>${size}</div></div><button type='button' class='btn-remove' onclick='removeFile(${idx})'>✖</button>`;"
                + "    queueEl.appendChild(card);"
                + "  });"
                + "  const dataTransfer = new DataTransfer();"
                + "  selectedFiles.forEach(f => dataTransfer.items.add(f));"
                + "  document.getElementById('filePicker').files = dataTransfer.files;"
                + "}"
                + "</script>"
                + "</body></html>";
    }

    static String resultsPage(JobPosting job, List<MatchResult> results, List<String> topSkills, Map<Candidate, List<JobRecommendation>> careerRecs, String mode) {
        StringBuilder cards = new StringBuilder();
        StringBuilder matrixRows = new StringBuilder();
        StringBuilder careerTabContent = new StringBuilder();
        StringBuilder shortlistText = new StringBuilder("EXECUTIVE CANDIDATE SHORTLIST REPORT\\nJob Title: " + job.getTitle() + "\\n\\n");

        int rank = 1;
        for (MatchResult r : results) {
            double pct = r.getScorePercent();
            String scoreClass = pct >= 40 ? "high" : pct >= 20 ? "mid" : "low";

            cards.append("<div class='card'>")
                 .append("<div class='card-header'>")
                 .append("<h3>#").append(rank++).append(" ").append(escape(r.getCandidate().getName()))
                 .append("<span class='tier-badge ").append(r.getTierClass()).append("'>").append(r.getTierLabel()).append("</span></h3>")
                 .append("<span class='score-ring ").append(scoreClass).append("'>")
                 .append(String.format("%.1f%%", pct)).append("</span>")
                 .append("</div>")

                 .append("<div class='fit-grid'>")
                 .append("<div class='fit-item'>Backend Architecture: <b>").append(r.getBackendFit()).append("</b></div>")
                 .append("<div class='fit-item'>Cloud & DevOps: <b>").append(r.getCloudFit()).append("</b></div>")
                 .append("<div class='fit-item'>Data & SQL: <b>").append(r.getDatabaseFit()).append("</b></div>")
                 .append("</div>")

                 .append("<div><b>Matched Skills:</b> ").append(tagList(r.getMatchedSkills(), "matched")).append("</div>")
                 .append("<div style='margin-top:6px;'><b>Skill Gaps:</b> ").append(tagList(r.getMissingSkills(), "missing")).append("</div>")

                 .append("<div class='probe-box'>")
                 .append("<b>❓ Suggested Technical Interview Probe:</b><br>")
                 .append(escape(r.getInterviewProbe()))
                 .append("</div>")
                 .append("</div>");

            matrixRows.append("<tr><td><b>").append(escape(r.getCandidate().getName())).append("</b><br><small style='color:var(--muted);'>")
                      .append(String.format("%.1f%% Match", pct)).append("</small></td>");
            for (String skill : topSkills) {
                boolean hasSkill = r.getMatchedSkills().contains(skill);
                matrixRows.append("<td class='").append(hasSkill ? "check-yes" : "check-no").append("'>")
                          .append(hasSkill ? "✔" : "✖").append("</td>");
            }
            matrixRows.append("</tr>");

            shortlistText.append(rank - 1).append(". ").append(r.getCandidate().getName())
                         .append(" - ").append(String.format("%.1f%%", pct)).append(" Match (").append(r.getTierLabel()).append(")\\n")
                         .append("   Matched: ").append(String.join(", ", r.getMatchedSkills())).append("\\n\\n");

            // Career Recommendations HTML for candidate
            careerTabContent.append("<div class='card'>")
                            .append("<h3>🚀 Job Role Suitability Recommendations for: ").append(escape(r.getCandidate().getName())).append("</h3>")
                            .append("<p class='subtitle'>AI-evaluated job roles based on skills extracted from their resume PDF:</p>");
            
            List<JobRecommendation> recs = careerRecs.getOrDefault(r.getCandidate(), List.of());
            for (JobRecommendation rec : recs) {
                double recPct = Math.round(rec.getScore() * 10000.0) / 100.0;
                String recClass = recPct >= 35 ? "good" : recPct >= 20 ? "warn" : "danger";
                careerTabContent.append("<div class='job-rec-card'>")
                                .append("<div class='job-rec-header'>")
                                .append("<span class='job-title-text'>💼 ").append(escape(rec.getRoleTitle())).append("</span>")
                                .append("<b style='color:var(--").append(recClass).append("); font-size:16px;'>")
                                .append(String.format("%.1f%% Suitability Match", recPct)).append("</b>")
                                .append("</div>")
                                .append("<div style='font-size:13px; color:var(--muted); margin-bottom:6px;'>").append(escape(rec.getRoleSummary())).append("</div>")
                                .append("<div><b>Matched Skills:</b> ").append(tagList(rec.getMatchedSkills(), "matched")).append("</div>")
                                .append("<div style='margin-top:4px;'><b>Skills to Acquire:</b> ").append(tagList(rec.getMissingSkills(), "missing")).append("</div>")
                                .append("<div class='probe-box' style='border-left-color:var(--good); margin-top:8px;'>")
                                .append("💡 <b>Career Bridge Tip:</b> ").append(escape(rec.getCareerTip()))
                                .append("</div>")
                                .append("</div>");
            }
            careerTabContent.append("</div>");
        }

        StringBuilder matrixHeader = new StringBuilder("<tr><th>Candidate</th>");
        for (String skill : topSkills) {
            matrixHeader.append("<th>").append(escape(skill)).append("</th>");
        }
        matrixHeader.append("</tr>");

        boolean isSeeker = "seeker".equalsIgnoreCase(mode);

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<title>Match & Career Dashboard</title>" + STYLE + "</head><body>"
                + "<div class='container'>"
                + "<a class='back' href='/'>&larr; Back to Input Form</a>"
                + "<div class='header-box' style='text-align:left;'>"
                + "<h1>" + (isSeeker ? "🚀 Recommended Career Roles for Your Resume" : "Results for: " + escape(job.getTitle())) + "</h1>"
                + "<p class='subtitle'>" + (isSeeker ? "AI analysis matching your uploaded resume PDF against top industry roles." : "AI-ranked candidate profiles, job suitability finder, side-by-side skill matrix, and interview guide.") + "</p>"
                + "</div>"

                + "<div class='tab-nav'>"
                + "<button class='tab-btn " + (isSeeker ? "" : "active") + "' onclick='showTab(\"rankings\", this)'>🏆 Ranked Profiles</button>"
                + "<button class='tab-btn " + (isSeeker ? "active" : "") + "' onclick='showTab(\"career\", this)'>🚀 Recommended Jobs Finder</button>"
                + "<button class='tab-btn' onclick='showTab(\"matrix\", this)'>📊 Skill Comparison Matrix</button>"
                + "<button class='tab-btn' onclick='showTab(\"export\", this)'>📋 HR Shortlist Report</button>"
                + "</div>"

                + "<div id='tab-rankings' style='display:" + (isSeeker ? "none" : "block") + ";'>" + cards + "</div>"
                + "<div id='tab-career' style='display:" + (isSeeker ? "block" : "none") + ";'>" + careerTabContent + "</div>"

                + "<div id='tab-matrix' style='display:none;'><div class='card'>"
                + "<h3>Side-by-Side Skill Matrix</h3>"
                + "<p class='subtitle'>Comparing candidates against top job description terms.</p>"
                + "<table class='matrix-table'><thead>" + matrixHeader + "</thead><tbody>" + matrixRows + "</tbody></table>"
                + "</div></div>"

                + "<div id='tab-export' style='display:none;'><div class='card'>"
                + "<h3>HR Shortlist & Executive Summary</h3>"
                + "<p class='subtitle'>Copy this summary for your hiring workflow.</p>"
                + "<textarea id='reportText' rows='12' style='font-family:monospace;'>" + shortlistText.toString().replace("\\n", "\n") + "</textarea>"
                + "<button class='btn-primary' style='margin-top:12px;' onclick='copyReport()'>📋 Copy Report to Clipboard</button>"
                + "</div></div>"

                + "</div>"
                + "<script>"
                + "function showTab(name, btn) {"
                + "  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));"
                + "  btn.classList.add('active');"
                + "  document.getElementById('tab-rankings').style.display = name==='rankings' ? 'block' : 'none';"
                + "  document.getElementById('tab-career').style.display = name==='career' ? 'block' : 'none';"
                + "  document.getElementById('tab-matrix').style.display = name==='matrix' ? 'block' : 'none';"
                + "  document.getElementById('tab-export').style.display = name==='export' ? 'block' : 'none';"
                + "}"
                + "function copyReport() {"
                + "  const t = document.getElementById('reportText'); t.select(); document.execCommand('copy');"
                + "  alert('HR Summary copied to clipboard!');"
                + "}"
                + "</script>"
                + "</body></html>";
    }

    static String errorPage(String message) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<title>Error</title>" + STYLE + "</head><body>"
                + "<div class='container'>"
                + "<a class='back' href='/'>&larr; Back to form</a>"
                + "<div class='error'>" + escape(message) + "</div>"
                + "</div></body></html>";
    }

    private static String tagList(List<String> terms, String cssClass) {
        if (terms.isEmpty()) return "<span class='hint'>none</span>";
        return terms.stream()
                .map(t -> "<span class='tag " + cssClass + "'>" + escape(t) + "</span>")
                .collect(Collectors.joining());
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

// ============================================================
//  MODELS & NLP ENGINE
// ============================================================

class Candidate {
    private final String id;
    private final String name;
    private final String resumeText;

    public Candidate(String id, String name, String resumeText) {
        this.id = id;
        this.name = name;
        this.resumeText = resumeText;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getResumeText() { return resumeText; }
}

class JobPosting {
    private final String title;
    private final String description;

    public JobPosting(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
}

class JobRecommendation {
    private final String roleTitle;
    private final String roleSummary;
    private final double score;
    private final List<String> matchedSkills;
    private final List<String> missingSkills;
    private final String careerTip;

    public JobRecommendation(String roleTitle, String roleSummary, double score,
                             List<String> matchedSkills, List<String> missingSkills, String careerTip) {
        this.roleTitle = roleTitle;
        this.roleSummary = roleSummary;
        this.score = score;
        this.matchedSkills = matchedSkills;
        this.missingSkills = missingSkills;
        this.careerTip = careerTip;
    }

    public String getRoleTitle() { return roleTitle; }
    public String getRoleSummary() { return roleSummary; }
    public double getScore() { return score; }
    public List<String> getMatchedSkills() { return matchedSkills; }
    public List<String> getMissingSkills() { return missingSkills; }
    public String getCareerTip() { return careerTip; }
}

class MatchResult implements Comparable<MatchResult> {
    private final Candidate candidate;
    private final double score;
    private final List<String> matchedSkills;
    private final List<String> missingSkills;
    private final String tierLabel;
    private final String tierClass;
    private final String backendFit;
    private final String cloudFit;
    private final String databaseFit;
    private final String interviewProbe;

    public MatchResult(Candidate candidate, double score,
                        List<String> matchedSkills, List<String> missingSkills,
                        String tierLabel, String tierClass,
                        String backendFit, String cloudFit, String databaseFit,
                        String interviewProbe) {
        this.candidate = candidate;
        this.score = score;
        this.matchedSkills = matchedSkills;
        this.missingSkills = missingSkills;
        this.tierLabel = tierLabel;
        this.tierClass = tierClass;
        this.backendFit = backendFit;
        this.cloudFit = cloudFit;
        this.databaseFit = databaseFit;
        this.interviewProbe = interviewProbe;
    }

    public Candidate getCandidate() { return candidate; }
    public double getScorePercent() { return Math.round(score * 10000.0) / 100.0; }
    public List<String> getMatchedSkills() { return matchedSkills; }
    public List<String> getMissingSkills() { return missingSkills; }
    public String getTierLabel() { return tierLabel; }
    public String getTierClass() { return tierClass; }
    public String getBackendFit() { return backendFit; }
    public String getCloudFit() { return cloudFit; }
    public String getDatabaseFit() { return databaseFit; }
    public String getInterviewProbe() { return interviewProbe; }

    @Override
    public int compareTo(MatchResult other) {
        return Double.compare(other.score, this.score);
    }
}

class TextPreprocessor {
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "is", "are", "was", "were",
            "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "that", "this",
            "years", "year", "experience", "strong", "including", "using", "used", "use", "work",
            "working", "role", "team", "teams", "job", "position", "candidate", "looking", "required"
    ));

    public static List<String> tokenize(String rawText) {
        if (rawText == null || rawText.isBlank()) return List.of();
        String cleaned = rawText.toLowerCase().replaceAll("[^a-z0-9+# ]", " ").replaceAll("\\s+", " ").trim();
        return Arrays.stream(cleaned.split(" "))
                .filter(w -> w.length() > 1 && !STOPWORDS.contains(w))
                .map(TextPreprocessor::stem)
                .collect(Collectors.toList());
    }

    private static String stem(String word) {
        if (word.matches(".*[+#].*")) return word;
        String[][] rules = {{"ational", "6"}, {"tional", "6"}, {"edly", "5"}, {"ies", "4"}, {"ing", "4"}, {"es", "3"}, {"ed", "3"}, {"s", "3"}};
        for (String[] r : rules) {
            if (word.endsWith(r[0]) && word.length() - r[0].length() >= Integer.parseInt(r[1])) {
                return word.substring(0, word.length() - r[0].length());
            }
        }
        return word;
    }
}

class TfIdfVectorizer {
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private int totalDocuments = 0;

    public void fit(List<List<String>> tokenizedDocuments) {
        totalDocuments = tokenizedDocuments.size();
        for (List<String> doc : tokenizedDocuments) {
            Set<String> uniqueTerms = new HashSet<>(doc);
            for (String term : uniqueTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
    }

    public Map<String, Double> vectorize(List<String> tokens, List<String> prioritySkills) {
        Map<String, Double> vector = new HashMap<>();
        if (tokens.isEmpty()) return vector;
        Map<String, Integer> rawCounts = new HashMap<>();
        for (String term : tokens) rawCounts.merge(term, 1, Integer::sum);

        Set<String> prioritySet = prioritySkills.stream().map(String::toLowerCase).collect(Collectors.toSet());

        int docLength = tokens.size();
        for (Map.Entry<String, Integer> entry : rawCounts.entrySet()) {
            String term = entry.getKey();
            double tf = entry.getValue() / (double) docLength;
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log((totalDocuments + 1) / (double) (df + 1)) + 1.0;
            
            double weightMultiplier = prioritySet.contains(term) ? 2.0 : 1.0;
            vector.put(term, tf * idf * weightMultiplier);
        }
        return vector;
    }
}

class CosineSimilarity {
    public static double compute(Map<String, Double> vA, Map<String, Double> vB) {
        if (vA.isEmpty() || vB.isEmpty()) return 0.0;
        Set<String> common = new HashSet<>(vA.keySet());
        common.retainAll(vB.keySet());
        double dot = 0.0;
        for (String t : common) dot += vA.get(t) * vB.get(t);
        double magA = mag(vA), magB = mag(vB);
        return (magA == 0 || magB == 0) ? 0.0 : dot / (magA * magB);
    }
    private static double mag(Map<String, Double> v) {
        double sum = 0;
        for (double w : v.values()) sum += w * w;
        return Math.sqrt(sum);
    }
}

class IndustryRoleCatalog {
    public static class RoleSpec {
        public final String title;
        public final String summary;
        public final String description;

        public RoleSpec(String title, String summary, String description) {
            this.title = title;
            this.summary = summary;
            this.description = description;
        }
    }

    public static final List<RoleSpec> ROLES = List.of(
        new RoleSpec("Senior Backend Java Engineer",
                     "Designing enterprise REST APIs, microservices, PostgreSQL databases, and high-throughput backend services.",
                     "Java Spring Boot microservices REST API PostgreSQL Docker Kubernetes AWS Kafka Jenkins JUnit"),
        new RoleSpec("Cloud & DevOps Infrastructure Specialist",
                     "Automating CI/CD pipelines, container orchestration, Kubernetes deployment, and cloud infrastructure.",
                     "Docker Kubernetes AWS Jenkins Kafka CI/CD DevOps Linux Infrastructure Cloud Terraform"),
        new RoleSpec("Full Stack Web Developer",
                     "Building responsive frontends in React/JS and integrating with backend REST microservices.",
                     "Java React JavaScript HTML CSS REST API TypeScript Node Next.js SQL Git"),
        new RoleSpec("Data Engineer & Analytics Specialist",
                     "Building data pipelines, SQL transformations, dashboards, and analytical reporting.",
                     "Python SQL PostgreSQL Pandas Data Visualization Dashboards Machine Learning Spark Hadoop ETL"),
        new RoleSpec("Enterprise Java Application Architect",
                     "Architecting legacy and cloud enterprise Java systems, ORM databases, and high-availability frameworks.",
                     "Java Spring Framework Hibernate MySQL Enterprise Microservices Architecture Design Patterns AWS")
    );
}

class ResumeMatcherEngine {
    private final TfIdfVectorizer vectorizer = new TfIdfVectorizer();

    public List<String> extractTopSkills(String jobDesc) {
        List<String> tokens = TextPreprocessor.tokenize(jobDesc);
        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokens) counts.merge(t, 1, Integer::sum);
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public List<MatchResult> rankCandidates(JobPosting job, List<Candidate> candidates, List<String> prioritySkills) {
        List<String> jobTokens = TextPreprocessor.tokenize(job.getDescription());
        Map<Candidate, List<String>> candidateTokens = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            candidateTokens.put(c, TextPreprocessor.tokenize(c.getResumeText()));
        }

        List<List<String>> corpus = new ArrayList<>();
        corpus.add(jobTokens);
        corpus.addAll(candidateTokens.values());
        vectorizer.fit(corpus);

        Map<String, Double> jobVector = vectorizer.vectorize(jobTokens, prioritySkills);
        List<String> importantJobTerms = extractTopSkills(job.getDescription());

        List<MatchResult> results = new ArrayList<>();
        for (Map.Entry<Candidate, List<String>> entry : candidateTokens.entrySet()) {
            Candidate c = entry.getKey();
            List<String> tokens = entry.getValue();

            Map<String, Double> resumeVector = vectorizer.vectorize(tokens, prioritySkills);
            double score = CosineSimilarity.compute(jobVector, resumeVector);
            Set<String> termSet = new HashSet<>(tokens);

            List<String> matched = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String term : importantJobTerms) {
                if (termSet.contains(term)) matched.add(term);
                else missing.add(term);
            }

            double pct = Math.round(score * 10000.0) / 100.0;
            String tierLabel = pct >= 35 ? "⭐ Top Contender" : pct >= 20 ? "👍 Strong Match" : "💡 Growth Candidate";
            String tierClass = pct >= 35 ? "top" : pct >= 20 ? "strong" : "low";

            String lower = c.getResumeText().toLowerCase();
            String backendFit = (lower.contains("java") || lower.contains("spring") || lower.contains("api")) ? "High" : "Moderate";
            String cloudFit = (lower.contains("aws") || lower.contains("docker") || lower.contains("kubernetes")) ? "High" : "Basic";
            String databaseFit = (lower.contains("sql") || lower.contains("postgres") || lower.contains("mysql")) ? "High" : "Basic";

            String probe = generateInterviewProbe(c.getName(), matched, missing);

            results.add(new MatchResult(c, score, matched, missing, tierLabel, tierClass, backendFit, cloudFit, databaseFit, probe));
        }

        Collections.sort(results);
        return results;
    }

    public List<JobRecommendation> recommendJobsForCandidate(Candidate candidate) {
        List<String> resumeTokens = TextPreprocessor.tokenize(candidate.getResumeText());
        Set<String> resumeTermSet = new HashSet<>(resumeTokens);

        List<List<String>> corpus = new ArrayList<>();
        corpus.add(resumeTokens);
        for (IndustryRoleCatalog.RoleSpec spec : IndustryRoleCatalog.ROLES) {
            corpus.add(TextPreprocessor.tokenize(spec.description));
        }

        TfIdfVectorizer recVectorizer = new TfIdfVectorizer();
        recVectorizer.fit(corpus);
        Map<String, Double> resumeVector = recVectorizer.vectorize(resumeTokens, List.of());

        List<JobRecommendation> recs = new ArrayList<>();
        for (IndustryRoleCatalog.RoleSpec spec : IndustryRoleCatalog.ROLES) {
            List<String> roleTokens = TextPreprocessor.tokenize(spec.description);
            Map<String, Double> roleVector = recVectorizer.vectorize(roleTokens, List.of());

            double sim = CosineSimilarity.compute(resumeVector, roleVector);

            List<String> matched = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String t : roleTokens) {
                if (resumeTermSet.contains(t)) matched.add(t);
                else missing.add(t);
            }

            String tip = missing.isEmpty() ? "Excellent skill match! Target Senior positions." :
                    "Acquiring skills in [" + String.join(", ", missing.subList(0, Math.min(2, missing.size()))) + "] will boost your qualification for " + spec.title + " roles!";

            recs.add(new JobRecommendation(spec.title, spec.summary, sim, matched, missing, tip));
        }

        recs.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return recs;
    }

    private String generateInterviewProbe(String name, List<String> matched, List<String> missing) {
        StringBuilder sb = new StringBuilder();
        if (!matched.isEmpty()) {
            sb.append("Deep-Dive Question: \"Can you explain your architectural design decisions when implementing ")
              .append(matched.get(0)).append(" in your recent production projects?\"\n");
        }
        if (!missing.isEmpty()) {
            sb.append("Skill-Gap Verification: \"This role relies heavily on ")
              .append(missing.get(0)).append(". What is your experience or strategy for adapting to ")
              .append(missing.get(0)).append(" in high-scale environments?\"");
        } else {
            sb.append("Solid alignment across core job skills! Ask about complex edge cases and system performance optimizations.");
        }
        return sb.toString();
    }
}
