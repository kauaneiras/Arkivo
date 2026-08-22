# ============================================
# Lab DMS — LaTeX Compilation Engine
# ============================================
# Security-hardened image for compiling .tex → PDF
# Used as EPHEMERAL containers (docker run --rm)
#
# Usage by backend:
#   docker run --rm \
#     --network none \
#     --memory=512m --cpus=1.0 \
#     --read-only \
#     --tmpfs /tmp:size=100M \
#     -v /path/to/input:/data:ro \
#     -v /path/to/output:/output \
#     lab-dms-latex:custom \
#     pdflatex -no-shell-escape \
#       -interaction=nonstopmode \
#       -output-directory=/output \
#       /data/document.tex

FROM ubuntu:24.04

LABEL maintainer="Lab DMS Team"
LABEL description="Secure LaTeX compilation engine for Lab DMS"

ENV DEBIAN_FRONTEND=noninteractive

# Install only necessary TeX Live packages (NOT texlive-full)
RUN apt-get update && apt-get install -y --no-install-recommends \
    texlive-latex-base \
    texlive-latex-extra \
    texlive-latex-recommended \
    texlive-fonts-recommended \
    texlive-fonts-extra \
    texlive-lang-portuguese \
    texlive-lang-french \
    texlive-lang-german \
    texlive-lang-english \
    texlive-science \
    texlive-pictures \
    texlive-bibtex-extra \
    biber \
    latexmk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Create non-root user for compilation
RUN groupadd -r latexuser && \
    useradd -r -g latexuser -d /home/latexuser -s /bin/false latexuser && \
    mkdir -p /data /output /home/latexuser && \
    chown -R latexuser:latexuser /data /output /home/latexuser

# Switch to non-root user
USER latexuser

# Set working directory
WORKDIR /data

# Default entrypoint: pdflatex with security flags
ENTRYPOINT ["pdflatex", "-no-shell-escape", "-interaction=nonstopmode"]
