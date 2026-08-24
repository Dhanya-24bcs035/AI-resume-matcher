# 🤖 AI Resume Matcher & Skill Analyst (Pure Java Web App)

A lightweight, high-performance web application that parses job descriptions and candidate resumes (via **direct PDF/TXT file upload** or pasted text), ranks candidates using **TF-IDF + Cosine Similarity**, generates a **side-by-side skill comparison matrix**, auto-classifies **seniority levels**, and generates **tailored technical interview probe questions**.

Built using **ONLY Java's built-in `com.sun.net.httpserver` package** — zero Spring Boot, zero Maven, and **zero external dependencies**!

---

## ✨ Features

- 📄 **Direct PDF & TXT File Upload**: Built-in pure Java PDF text decompression engine (`java.util.zip.InflaterInputStream`) without Apache PDFBox or external libraries.
- 🏆 **TF-IDF + Cosine Similarity Scoring**: Mathematical vector similarity match between job description requirements and candidate resumes.
- 📊 **Interactive Side-by-Side Skill Matrix**: Visual comparison grid displaying matched (`✔`) and missing (`✖`) skills across all candidates.
- ❓ **Smart Technical Interview Probe Generator**: Tailored deep-dive questions for verified skills and gap-probing questions for missing skills.
- 🎯 **Candidate Seniority Badging**: Classifies candidates into `Senior / Lead`, `Mid-Level`, or `Junior / Specialist`.
- 📋 **Executive HR Shortlist Export**: One-click summary generation for hiring managers.
- 🎨 **Modern Dark Glassmorphic Dashboard**: Tabbed UI with animated score gauges and responsive design.

---

## 🚀 Quick Start (Local Execution)

### Prerequisites
- Any Java Development Kit (JDK 17 or higher recommended).

### Running the App

```bash
# 1. Compile Main.java
javac Main.java

# 2. Run the Server
java Main
```

Open your browser and navigate to:
```
http://localhost:8080
```
*(If port 8080 is in use, set `PORT=8081 java Main` or `$env:PORT="8081"; java Main` in PowerShell).*

---

## 📂 Project Structure

```
ai-resume-matcher/
├── Main.java              # Complete single-file application (HTTP Server, PDF Extractor, NLP Engine, UI Dashboard)
└── README.md              # Documentation
```

---

## 📄 License

MIT License. Free to use and modify!
