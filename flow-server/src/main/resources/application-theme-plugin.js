const fs = require('fs');
const { compileFunction } = require("vm");
const glob = require('glob');
const camelCase = require('camelCase');
const path = require('path');

// Special folder inside a theme for component themes that go inside the component shadow root
const themeComponentsFolder = 'components';
// The contents of a global CSS file with this name in a theme is always added to the document. E.g. @font-face must be in this
const themeFileAlwaysAddToDocument = 'document.css';
let logger;

class ApplicationThemePlugin {
    constructor(options) {
        this.options = options;
    }

    apply(compiler) {
        logger = compiler.getInfrastructureLogger("application-theme-plugin");

        compiler.hooks.run.tap("FlowApplicationThemePlugin", (compiler) => {
            if (fs.existsSync(this.options.themeJarFolder)) {
                handleThemes(this.options.themeJarFolder, this.options.projectStaticAssetsOutputFolder);
            } else {
                logger.warn('Theme JAR folder not found from ', this.options.themeJarFolder);
            }

            this.options.themeProjectFolders.forEach((themeProjectFolder) => {
                if (fs.existsSync(themeProjectFolder)) {
                    handleThemes(themeProjectFolder, this.options.projectStaticAssetsOutputFolder);
                }
            });
        });
    }
}

module.exports = ApplicationThemePlugin;

const headerImport = `import { css, unsafeCSS, registerStyles } from '@vaadin/vaadin-themable-mixin/register-styles'; 
import 'construct-style-sheets-polyfill';
`;
const injectGlobalCssMethod = `// target: Document | ShadowRoot
export const injectGlobalCss = (css, target) => {
  const sheet = new CSSStyleSheet();
  sheet.replaceSync(css);
  target.adoptedStyleSheets = [...target.adoptedStyleSheets, sheet];
};
`;
const importCssUrlMethod = `export const importCssUrl = (url, target) => {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = url;
  if (target.head) {
    target.head.appendChild(link);
  } else {
    target.appendChild(link);
  }
};
`;

function getThemeProperties(themeFolder) {
    const themePropertyFile = path.resolve(themeFolder, 'theme.json');
    if (!fs.existsSync(themePropertyFile)) {
        return {};
    }
    return JSON.parse(fs.readFileSync(themePropertyFile));
};

function generateThemeFile(themeFolder, themeName, themeProperties) {
    const globalFiles = glob.sync('*.css', {
        cwd: themeFolder,
        nodir: true,
    });
    const componentsFiles = glob.sync('*.css', {
        cwd: path.resolve(themeFolder, themeComponentsFolder),
        nodir: true,
    });

    let themeFile = headerImport;

    if (themeProperties.parent) {
        themeFile += `import {applyTheme as applyBaseTheme} from 'theme/${themeProperties.parent}/${themeProperties.parent}.js';`;
    }

    themeFile += injectGlobalCssMethod;
    themeFile += importCssUrlMethod;

    const imports = [];
    const globalCssCode = [];
    const componentCssCode = [];
    const parentTheme = themeProperties.parent ? 'applyBaseTheme(target);\n' : '';
    globalFiles.forEach((global) => {
        const filename = path.basename(global);
        const variable = camelCase(filename);
        imports.push(`import ${variable} from './${filename}';\n`);
        if (filename == themeFileAlwaysAddToDocument) {
            globalCssCode.push(`injectGlobalCss(${variable}.toString(), document);\n`);
        }
        globalCssCode.push(`injectGlobalCss(${variable}.toString(), target);\n`);
    });

    let i = 0;
    if (themeProperties.css) {
        themeProperties.css.forEach((cssImport) => {
            const variable = 'module' + i++;
            imports.push(`import ${variable} from '${cssImport}';\n`);
            globalCssCode.push(`injectGlobalCss(${variable}.toString(), target);\n`);
        });
    }
    if (themeProperties.documentCss) {
        themeProperties.documentCss.forEach((cssImport) => {
            const variable = 'module' + i++;
            imports.push(`import ${variable} from '${cssImport}';\n`);
            globalCssCode.push(`    injectGlobalCss(${variable}.toString(), target);\n`);
            globalCssCode.push(`    injectGlobalCss(${variable}.toString(), document);\n`);
        });
    }
    if (themeProperties.externalCss) {
        themeProperties.externalCss.forEach((cssUrl) => {
            globalCssCode.push(`importCssUrl('${cssUrl}', target);\n`);
            globalCssCode.push(`importCssUrl('${cssUrl}', document);\n`);
        });
    }
    componentsFiles.forEach((componentCss) => {
        const filename = path.basename(componentCss);
        const tag = filename.replace('.css', '');
        const variable = camelCase(filename);
        imports.push(
          `import ${variable} from './${themeComponentsFolder}/${filename}';\n`
        );
        const componentString = `registerStyles(
      '${tag}',
      css\`
        \${unsafeCSS(${variable}.toString())}
      \`
    );
`;
        componentCssCode.push(componentString);
    });

    const themeIdentifier = '_vaadinds_' + themeName + '_';
    const globalCssFlag = themeIdentifier + 'globalCss';
    const componentCssFlag = themeIdentifier + 'componentCss';

    themeFile += imports.join('');

    const themeFileApply = `export const applyTheme = (target) => {
  ${parentTheme}
  if (!target['${globalCssFlag}']) {
    ${globalCssCode.join('')}
    target['${globalCssFlag}'] = true;
  }
  if (!document['${componentCssFlag}']) {
    ${componentCssCode.join('')}
    document['${componentCssFlag}'] = true;
  }
}
`;

    themeFile += themeFileApply;

    return themeFile;
};

function handleThemes(themesFolder, projectStaticAssetsOutputFolder) {
    logger.info("handling theme from ", themesFolder);
    const dir = fs.opendirSync(themesFolder);
    while ((dirent = dir.readSync())) {
        if (!dirent.isDirectory()) {
            continue;
        }
        const themeName = dirent.name;
        const themeFolder = path.resolve(themesFolder, themeName);
        const themeProperties = getThemeProperties(themeFolder);
        logger.info("Found theme ", themeName, " in folder ", themeFolder);

        copyStaticAssets(themeProperties, projectStaticAssetsOutputFolder);
        const themeFile = generateThemeFile(
          themeFolder,
          themeName,
          themeProperties
        );
        fs.writeFileSync(path.resolve(themeFolder, themeName + '.js'), themeFile);
    }
};

function copyStaticAssets(themeProperties, projectStaticAssetsOutputFolder) {

    const assets = themeProperties.assets;
    if (!assets) {
        logger.info("no assets to handle no static assets were copied");
        return;
    }

    fs.mkdirSync(projectStaticAssetsOutputFolder, {
        recursive: true
    });
    Object.keys(assets).forEach((module) => {
        const rules = assets[module];
        Object.keys(rules).forEach((srcSpec) => {
            const files = glob.sync('node_modules/' + module + '/' + srcSpec, {
                nodir: true,
            });
            const targetFolder = path.resolve(
              projectStaticAssetsOutputFolder,
              rules[srcSpec]
            );
            fs.mkdirSync(targetFolder, {
                recursive: true
            });
            files.forEach((file) => {
                logger.trace("Copying: ", file, '=>', targetFolder);
                fs.copyFileSync(file, path.resolve(targetFolder, path.basename(file)));
            });
        });
    });
};
