FROM node:20-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY server.mjs ./
COPY public ./public
ENV PORT=8092
EXPOSE 8092
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD wget -q -O /dev/null http://127.0.0.1:8092/healthz || exit 1
CMD ["node", "server.mjs"]
