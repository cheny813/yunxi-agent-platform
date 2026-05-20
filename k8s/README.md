# yunxi Agent Platform Kubernetes Deployment

## Prerequisites

- Kubernetes 1.24+
- kubectl configured
- Ingress Controller (nginx)
- Prometheus Operator (optional, for monitoring)

## Quick Start

```bash
# Create namespace
kubectl apply -f namespace.yaml

# Apply ConfigMap
kubectl apply -f configmap.yaml

# Deploy application
kubectl apply -f deployment.yaml

# Create services
kubectl apply -f service.yaml

# Apply HPA
kubectl apply -f hpa.yaml

# Apply Ingress (optional, requires ingress controller)
kubectl apply -f ingress.yaml

# Apply ServiceMonitor (optional, requires Prometheus Operator)
kubectl apply -f service-monitor.yaml
```

## Configuration

Edit `configmap.yaml` to customize application settings:
- Session storage type (memory/sqlite)
- Database path
- Cleanup intervals

## Monitoring

The application exposes Prometheus metrics at `/actuator/prometheus` on port 9090.

## Scaling

HPA is configured to scale based on:
- CPU utilization (target: 70%)
- Memory utilization (target: 80%)

Min replicas: 2
Max replicas: 10
