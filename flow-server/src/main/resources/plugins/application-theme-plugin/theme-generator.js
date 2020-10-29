/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

/**
 * This file handles the generation of the '[theme-name].js' to
 * the theme/[theme-name] folder according to properties from 'theme.json'.
 */

const glob = require('glob');
const path = require('path');
const camelCase = require('camelCase');

// Special folder inside a theme for component themes that go inside the component shadow root
const themeComponentsFolder = 'components';
// The contents of a global CSS file with this name in a theme is always added to
// the document. E.g. @font-face must be in this
const themeFileAlwaysAddToDocument = 'document.css';

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
// Don't format as the generated file formatting will get wonky!
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

// Don't format as the generated file formatting will get wonky!
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

module.exports = generateThemeFile;