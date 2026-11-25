@echo off
setlocal
set "PYTHONPATH=%~dp0"
set "VENV_PYTHON=%~dp0.venv\Scripts\python.exe"

if not exist "%VENV_PYTHON%" (
    python -m venv "%~dp0.venv" || exit /b 1
)

"%VENV_PYTHON%" -m pip install -r "%~dp0requirements.txt" || exit /b 1
"%VENV_PYTHON%" -m tv_setup
exit /b %errorlevel%
