import { cp, mkdir, readdir, rm, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

export function resolveRepositoryRoot() {
  return resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
}

export function resolveWebConsolePaths(repositoryRoot = resolveRepositoryRoot()) {
  const root = resolve(repositoryRoot);
  return {
    repositoryRoot: root,
    rendererDir: join(root, "collector-desktop", "dist", "renderer"),
    staticDesktopDir: join(root, "src", "main", "resources", "static", "desktop"),
    publicPath: "/desktop/index.html"
  };
}

export async function copyWebConsoleBuild(repositoryRoot = resolveRepositoryRoot()) {
  const paths = resolveWebConsolePaths(repositoryRoot);
  const rendererIndex = join(paths.rendererDir, "index.html");
  try {
    const indexStat = await stat(rendererIndex);
    if (!indexStat.isFile()) {
      throw new Error("renderer index is not a file");
    }
  } catch (error) {
    throw new Error(`未找到 Vue 构建产物，请先执行 npm run build:renderer：${rendererIndex}`, { cause: error });
  }

  await rm(paths.staticDesktopDir, { recursive: true, force: true });
  await mkdir(paths.staticDesktopDir, { recursive: true });
  await cp(paths.rendererDir, paths.staticDesktopDir, { recursive: true });

  return {
    ...paths,
    copiedFiles: await countFiles(paths.staticDesktopDir)
  };
}

async function countFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  let total = 0;
  for (const entry of entries) {
    const childPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      total += await countFiles(childPath);
    } else if (entry.isFile()) {
      total += 1;
    }
  }
  return total;
}
