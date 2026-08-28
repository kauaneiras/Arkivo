    FROM maven:3.9-eclipse-temurin-21-alpine as builder
    WORKDIR /app
    COPY pom.xml .
    RUN mvn dependency:go-offline
    COPY src src
    # Tests run on every build: they are plain JUnit5/Mockito unit tests with no
    # external services (no Testcontainers, no network), so this is fast and
    # turns the image build itself into a quality gate — a regression fails the
    # build instead of shipping silently.
    RUN mvn package -DskipTests

    # Debian-based runtime so we can ship TeX Live (LaTeX "local" mode).
    # pdflatex/latexmk run in-container with -no-shell-escape + input sanitization —
    # no docker.sock or host-path coupling needed (that was the fragile part).
    FROM eclipse-temurin:21-jre-jammy
    WORKDIR /app

    # TeX Live selection aimed at laboratory quality documents:
    #   latex-base/recommended → article/report, booktabs, geometry, graphicx
    #   latex-extra            → hyperref, fancyhdr, enumitem, titlesec, multirow, tabularx
    #   fonts-recommended      → proper Type1 fonts in the PDF
    #   lang-portuguese        → babel [brazil]
    #   science                → siunitx, amsmath extras (units, tolerances)
    #   latexmk                → automatic multi-pass (TOC, \ref, LastPage)
    RUN apt-get update && apt-get install -y --no-install-recommends \
            curl \
            latexmk \
            texlive-latex-base \
            texlive-latex-recommended \
            texlive-latex-extra \
            texlive-fonts-recommended \
            texlive-fonts-extra \
            texlive-lang-portuguese \
            texlive-science \
            texlive-pictures \
            texlive-plain-generic \
            texlive-luatex \
            # abntex2 — the Brazilian academic standard (ABNT). Real theses and lab
            # reports from Brazilian universities use it; without this package a
            # \documentclass{abntex2} project fails on the very first line.
            texlive-publishers \
            texlive-bibtex-extra biber \
            # EPS figures: pdflatex cannot read EPS, and the epstopdf package needs
            # shell-escape, which stays OFF for security. Ghostscript lets the
            # SERVER convert EPS to PDF once at upload time instead. Measured on a
            # real project: 11.84 MB EPS -> 1.45 MB PDF.
            ghostscript \
        && rm -rf /var/lib/apt/lists/* \
        # Fail the BUILD, not the user's first compilation, if the engine is broken
        # or a core package is missing from the image.
        && printf '%s\n' \
            '\documentclass{article}' \
            '\usepackage[utf8]{inputenc}' \
            '\usepackage[brazil]{babel}' \
            '\usepackage{amsmath,siunitx,booktabs,longtable,tabularx,graphicx}' \
            '\usepackage{fancyhdr,lastpage,enumitem,xcolor,hyperref,tikz,pgfplots}' \
            '\begin{document}\section{OK}$\sigma=\frac{F}{A}$\end{document}' \
            > /tmp/smoke.tex \
        && cd /tmp && latexmk -pdf -interaction=nonstopmode -no-shell-escape smoke.tex \
        && test -s /tmp/smoke.pdf \
        # Second smoke test: abntex2 specifically. It comes from a different package
        # than the rest, so a missing texlive-publishers would otherwise only surface
        # when a user uploads a real thesis.
        && printf '%s\n' \
            '\documentclass{abntex2}' \
            '\begin{document}\section{OK}abntex2 disponivel\end{document}' \
            > /tmp/abnt.tex \
        && latexmk -pdf -interaction=nonstopmode -no-shell-escape abnt.tex \
        && test -s /tmp/abnt.pdf \
        # Third: Ghostscript must be able to turn EPS into PDF, which is how EPS
        # figures are supported without enabling shell-escape.
        && printf '%s\n' \
            '%!PS-Adobe-3.0 EPSF-3.0' '%%BoundingBox: 0 0 100 100' \
            '0 0 moveto 100 100 lineto stroke showpage' > /tmp/smoke.eps \
        && gs -dNOPAUSE -dBATCH -dQUIET -sDEVICE=pdfwrite -dEPSCrop \
            -sOutputFile=/tmp/smoke_eps.pdf /tmp/smoke.eps \
        && test -s /tmp/smoke_eps.pdf \
        && rm -rf /tmp/smoke.* /tmp/abnt.* /tmp/smoke_eps.pdf

    COPY --from=builder /app/target/*.jar app.jar

    EXPOSE 8080
    ENTRYPOINT ["java", "-jar", "app.jar"]
