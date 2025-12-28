import toast, { ToastOptions } from 'react-hot-toast';

export const toastWarning = (message: string, options?: ToastOptions) => {
  toast(message, {
    icon: '⚠️',
    style: {
      border: '1px solid #FFC107',
      padding: '16px',
      color: '#333',
      whiteSpace: 'nowrap',
      maxWidth: '90vw', 
    },
    ...options,
  });
};

export const confirmToast = (message: string, onConfirm: () => void) => {
  toast((t) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
      
      <div style={{ fontSize: '14px', fontWeight: 500, color: '#363636' }}>
        {message}
      </div>

      <div style={{ width: '1px', height: '20px', background: '#E0E0E0' }} />

      <button
        onClick={() => toast.dismiss(t.id)}
        style={{
          border: 'none',
          background: 'transparent',
          color: '#666',
          cursor: 'pointer',
          padding: '4px 8px',
          fontSize: '13px'
        }}
      >
        Cancel
      </button>

      <button
        onClick={() => {
          onConfirm();
          toast.dismiss(t.id);
        }}
        style={{
          border: 'none',
          background: '#ff4b4b',
          color: 'white',
          borderRadius: '4px',
          cursor: 'pointer',
          padding: '5px 10px',
          fontSize: '13px',
          fontWeight: 'bold'
        }}
      >
        Delete
      </button>
    </div>
  ), {
    duration: 5000,
    position: 'top-center',
    style: {
      marginTop: '90px',
      background: '#fff',
      color: '#363636',
      boxShadow: '0 3px 10px rgba(0,0,0,0.1), 0 3px 3px rgba(0,0,0,0.05)',
      borderRadius: '8px',
      padding: '8px 12px',
    },
  });
};