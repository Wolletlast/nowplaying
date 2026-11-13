'use strict';
// Мост для карточки: сообщает главному процессу, когда пользователь взял и отпустил карточку.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('npDrag', {
  start: () => ipcRenderer.send('np-drag', 'start'),
  end: () => ipcRenderer.send('np-drag', 'end'),
});
