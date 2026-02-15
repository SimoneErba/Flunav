#!/bin/bash
set -e

echo "[init-script] Eseguo lo script di inizializzazione del database..."

# La variabile ORIENTDB_ROOT_PASSWORD è già disponibile nell'ambiente del container.
# Questo comando si connette al server locale (già avviato dall'entrypoint ufficiale)
# e crea il database 'main' se non esiste.
/orientdb/bin/console.sh "CREATE DATABASE main root root plocal graph; exit"

echo "[init-script] Script di inizializzazione completato."