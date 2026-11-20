FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY scripts ./scripts
# Run as the unprivileged user that ships with the base image.
USER node
ENV PORT=8080
EXPOSE 8080
CMD ["node", "src/server.js"]
