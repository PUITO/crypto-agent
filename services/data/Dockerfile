FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY libs /libs
COPY services/data/app /app

ENV PYTHONPATH=/libs
EXPOSE 8001

CMD ["python", "main.py"]
