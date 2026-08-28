# ============================================
# Lab DMS Frontend — React + Vite + Nginx
# ============================================
FROM node:20-alpine AS builder

WORKDIR /app
COPY package*.json ./
# npm install (not ci) tolerates a lockfile that lags behind package.json
RUN npm install

COPY . .

# Vite reads env vars at BUILD time. Default is a relative URL,
# proxied by nginx below to the backend container.
ARG VITE_API_URL=/api/v1
ENV VITE_API_URL=${VITE_API_URL}

RUN npm run build

# ============================================
# Production server — Nginx (SPA + API proxy)
# ============================================
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html

RUN printf 'server {\n\
    listen 3000;\n\
    client_max_body_size 20M;\n\
\n\
    # The pdf.js worker is an ES module, shipped as .mjs — and nginx bundled\n\
    # mime.types has no entry for .mjs, so it fell through to default_type and\n\
    # went out as application/octet-stream. Chrome refuses to start a module\n\
    # worker whose script does not carry a JavaScript MIME type, so pdf.js\n\
    # never initialised: the PDF bytes arrived intact and the viewer panel\n\
    # stayed empty ("Failed to load PDF file").\n\
    #\n\
    # Fixed with a location rather than a types block on purpose: a types block\n\
    # inside server REPLACES the inherited map, which would leave CSS, fonts and\n\
    # images without a type. default_type only applies when the map has no\n\
    # match, so this touches .mjs and nothing else.\n\
    location ~ \\.mjs$ {\n\
        root /usr/share/nginx/html;\n\
        default_type text/javascript;\n\
        try_files $uri =404;\n\
    }\n\
\n\
    # API proxy -> backend container (same-origin, no CORS)\n\
    #\n\
    # O destino vem de uma VARIAVEL, e nao escrito diretamente no proxy_pass.\n\
    # Com o nome literal, o nginx resolve "backend" no arranque e aborta se o\n\
    # nome ainda nao existir: durante o "docker build" nao ha rede do compose,\n\
    # entao o nginx -t abaixo falhava a construcao inteira; e em execucao, subir\n\
    # o frontend antes do backend derrubava o frontend tambem.\n\
    # Com variavel, a resolucao passa a ser por pedido, atraves do DNS interno\n\
    # do Docker (127.0.0.11) — o nginx arranca e vai tentando.\n\
    #\n\
    # $request_uri traz o caminho completo E a query string, e a localizacao\n\
    # /api/ aponta para /api/ do outro lado, entao o prefixo e o mesmo dos dois\n\
    # lados e nada precisa de ser reescrito.\n\
    resolver 127.0.0.11 ipv6=off valid=10s;\n\
    location /api/ {\n\
        set $upstream_backend http://backend:8080;\n\
        proxy_pass $upstream_backend$request_uri;\n\
        proxy_set_header Host $host;\n\
        proxy_set_header X-Real-IP $remote_addr;\n\
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto $scheme;\n\
        proxy_read_timeout 120s;\n\
    }\n\
\n\
    # Assets carregam o hash do conteudo no proprio nome, entao podem ser\n\
    # guardados para sempre: mudou o conteudo, muda o nome.\n\
    # Sem "expires": ele emite o seu proprio Cache-Control e a resposta sairia\n\
    # com DOIS cabecalhos iguais e conflitantes. Cache-Control tem precedencia\n\
    # sobre Expires em qualquer navegador HTTP/1.1, entao um so basta.\n\
    location /assets/ {\n\
        root /usr/share/nginx/html;\n\
        add_header Cache-Control "public, max-age=31536000, immutable" always;\n\
    }\n\
\n\
    # O index.html NUNCA pode ser guardado em cache.\n\
    #\n\
    # Ele e o unico arquivo com nome fixo, e e ele que diz quais pacotes com\n\
    # hash carregar. Uma copia em cache prende o navegador a um pacote antigo:\n\
    # o container e atualizado, a pagina continua carregando o JavaScript de\n\
    # ontem, e toda alteracao entregue parece nunca ter sido feita. Era por isso\n\
    # que "aperte CTRL+SHIFT+R" vivia sendo necessario — remendo para um\n\
    # cabecalho que faltava, nao mania do navegador.\n\
    location / {\n\
        root /usr/share/nginx/html;\n\
        index index.html;\n\
        try_files $uri $uri/ /index.html;\n\
        add_header Cache-Control "no-store, no-cache, must-revalidate" always;\n\
    }\n\
}\n' > /etc/nginx/conf.d/default.conf

# Falha a construção se a configuração acima estiver mal formada, em vez de o
# contêiner subir e servir errado.
RUN nginx -t

EXPOSE 3000
CMD ["nginx", "-g", "daemon off;"]
