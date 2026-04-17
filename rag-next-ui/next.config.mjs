/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  env: {
    RAG_API_URL: process.env.RAG_API_URL || 'http://localhost:8080',
  },
};

export default nextConfig;
