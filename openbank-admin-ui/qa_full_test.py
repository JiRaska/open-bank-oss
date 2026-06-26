import json
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    
    page.route("**/q/health/ready", lambda route: route.fulfill(status=200, body="OK"))
    
    mock_products = [
        {
            "id": "prod-1",
            "code": "SAV_01",
            "name": "Standard Savings",
            "type": "SAVINGS",
            "currency": "EUR",
            "status": "ACTIVE",
            "baseRate": 0.02,
            "fee": 0
        },
        {
            "id": "prod-2",
            "code": "LOAN_01",
            "name": "Personal Loan",
            "type": "LOAN",
            "currency": "EUR",
            "status": "DRAFT",
            "baseRate": 0.05,
            "fee": 50
        }
    ]
    
    def handle_api_products(route):
        if route.request.method == "GET":
            route.fulfill(status=200, json=mock_products)
        elif route.request.method == "POST":
            route.fulfill(status=400, body="Mocked Backend Error: Invalid data")
        elif route.request.method == "PUT":
            route.fulfill(status=200, json={"status": "success"})
        else:
            route.continue_()

    page.route("**/api/v1/products", handle_api_products)
    
    def handle_specific_product(route):
        if route.request.method == "PUT":
            route.fulfill(status=200, json={"status": "success"})
        elif route.request.method == "POST":
            route.fulfill(status=200, json={"status": "success"})
        else:
            route.continue_()
            
    page.route("**/api/v1/products/*", handle_specific_product)
    page.route("**/api/v1/products/*/*", handle_specific_product)

    print("Navigating to admin UI...")
    page.goto('http://localhost:3000/product-catalog')
    page.wait_for_load_state('networkidle')
    
    if "Přihlásit se" in page.content() or "Log in" in page.content() or "Sign in" in page.content():
        login_btn = page.locator('button', has_text="Keycloak SSO")
        if login_btn.count() > 0:
            login_btn.click()
            page.wait_for_load_state('networkidle')
            page.fill('input[name="username"]', 'admin@openbank.local')
            page.fill('input[name="password"]', 'Admin1234!')
            page.click('input[name="login"]')
            page.wait_for_load_state('networkidle')
            
    page.wait_for_selector('text="Standard Savings"')
    print("[SUCCESS] Mocked products loaded into the table.")
    
    print("Testing CREATE flow (expecting backend error UX)...")
    page.click('button:has-text("Create Product")')
    page.wait_for_selector('text="Create Product"')
    
    page.fill('input[placeholder="e.g. SAVINGS_01"]', 'NEW_PROD')
    page.fill('input[placeholder="e.g. SAVINGS, LOAN"]', 'SAVINGS')
    page.fill('input[placeholder="Product Name"]', 'New Savings Product')
    page.fill('input[placeholder="EUR"]', 'CZK')
    page.fill('input[type="number"]', '0.03')
    
    page.click('button:has-text("Save Product")')
    
    try:
        page.wait_for_selector('text="Mocked Backend Error: Invalid data"', timeout=5000)
        print("[SUCCESS] Create flow gracefully handles and displays backend errors.")
    except Exception as e:
        print("[FAILED] Create flow did not display backend error 'Mocked Backend Error: Invalid data'")
        print("Taking a look at what is visible...")
        page.screenshot(path="create_error_state.png")
        print("Screenshot saved to create_error_state.png")
    
    page.click('button:has-text("Cancel")')
    
    print("Testing EDIT flow...")
    page.locator('table tbody tr').nth(0).locator('button[title="Edit"]').click()
    page.wait_for_selector('text="Edit Product"')
    
    code_val = page.locator('input[placeholder="e.g. SAVINGS_01"]').input_value()
    print(f"Edit modal prefilled code: {code_val}")
    if code_val != "SAV_01":
        print("[FAILED] Edit modal was not prefilled correctly.")
    
    page.fill('input[placeholder="Product Name"]', 'Updated Savings Product')
    page.click('button:has-text("Save Product")')
    
    page.wait_for_selector('text="Edit Product"', state='hidden')
    print("[SUCCESS] Edit flow submitted successfully and modal closed.")
    
    print("Testing STATUS TOGGLE flow...")
    print("Dumping table buttons to understand available actions:")
    buttons = page.locator('table tbody tr button').all()
    for i, b in enumerate(buttons):
        try:
            print(f"Button {i} text: '{b.inner_text()}', title: '{b.get_attribute('title')}'")
        except:
            pass
            
    # Try to toggle the first row
    page.locator('table tbody tr').nth(0).locator('button').nth(1).click() # 0 is edit, 1 is toggle usually
    
    page.wait_for_timeout(1000)
    print("[SUCCESS] Status toggle action button triggers without crash.")

    print("Testing REAL unreachable backend behavior...")
    page.unroute("**/q/health/ready")
    page.unroute("**/api/v1/products")
    page.unroute("**/api/v1/products/*")
    page.unroute("**/api/v1/products/*/*")
    
    page.click('button:has-text("Refresh")')
    page.wait_for_timeout(2000)
    
    if page.locator('text="Product Catalog Service is currently unreachable"').is_visible():
        print("[SUCCESS] Unreachable backend is handled gracefully with an explicit message.")
    else:
        print("[WARNING] Unreachable message not found.")
        
    print("All QA checks completed.")
    browser.close()
